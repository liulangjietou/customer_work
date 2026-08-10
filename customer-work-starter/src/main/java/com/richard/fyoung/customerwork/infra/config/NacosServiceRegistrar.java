package com.richard.fyoung.customerwork.infra.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Nacos 服务注册器（裸 nacos-client 的 {@link NamingService}，与 {@code NacosPromptService} 同模式）。
 *
 * <p>启动时把当前应用注册为一个<b>临时实例</b>（ephemeral，nacos-client 自带心跳续约），停机时反注册，
 * 供 Spring Cloud Gateway 经 {@code lb://serviceName} 做服务发现路由。不引入 spring-cloud-alibaba 到
 * 业务模块——注册只用 {@code NamingService.registerInstance}，连接属性构建方式与 starter 既有 Nacos 服务一致。</p>
 *
 * <p><b>Fail-safe</b>：Nacos 不可达 / 鉴权失败时 {@code catch(Exception)} 记 error 后返回，
 * <b>绝不阻断应用启动</b>（与 {@code NacosPromptService} 的降级语义一致）——注册失败只是网关发现不到本实例，
 * 不影响本应用自身对外服务。本类为可复用普通类：app-server 与 admin-server 各自按配置构造并交给 Spring
 * 托管，由容器触发 {@link PostConstruct} / {@link PreDestroy}。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class NacosServiceRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosServiceRegistrar.class);

    private static final String CODE_REGISTER_FAIL = "NACOS-SERVICE-REGISTER-FAIL";
    private static final String CODE_DEREGISTER_FAIL = "NACOS-SERVICE-DEREGISTER-FAIL";
    private static final String META_SCHEME = "scheme";
    private static final String META_CONTEXT_PATH = "contextPath";

    private final NacosRegistration registration;
    /** 建连后的 NamingService；注册失败时保持 null，反注册即无操作。 */
    private volatile NamingService namingService;
    /** 已注册实例快照，反注册时按同一实例撤销。 */
    private volatile Instance instance;

    public NacosServiceRegistrar(NacosRegistration registration) {
        this.registration = registration;
    }

    /** 应用启动时注册临时实例；失败不阻断启动。 */
    @PostConstruct
    public void register() {
        // 局部持有：createNamingService 一旦成功即已启动 gRPC 后台重连线程，若随后 registerInstance 抛异常
        // （nacos 不可达的 fail-safe 主路径），naming 未赋字段、@PreDestroy 也兜不到——孤儿 NamingService 的
        // 后台重连线程会无限重试。故 catch 里必须对已建实例 shutDown，确保降级后不留后台线程/资源残留。
        NamingService naming = null;
        try {
            naming = NacosFactory.createNamingService(buildProperties());
            Instance ins = buildInstance();
            naming.registerInstance(registration.getServiceName(), registration.getGroup(), ins);
            this.namingService = naming;
            this.instance = ins;
            log.info("[Nacos] service registered, service={}, ip={}, port={}, group={}, namespace={}",
                registration.getServiceName(), ins.getIp(), ins.getPort(),
                registration.getGroup(), registration.getNamespace());
        } catch (Exception e) {
            // Nacos 不可达不应阻断启动：网关发现不到本实例，但本应用自身对外服务不受影响
            log.error("nacos service register failed, keep serving without discovery, code={}, service={}",
                CODE_REGISTER_FAIL, registration.getServiceName(), e);
            // 释放已创建但注册失败的 NamingService，避免后台重连线程残留（字段仍为 null，@PreDestroy 无操作）
            shutDownQuietly(naming);
        }
    }

    /** 安静释放 NamingService（关闭 gRPC 重连线程池等后台资源）；null 或异常均吞掉，仅用于兜底清理。 */
    private void shutDownQuietly(NamingService naming) {
        if (naming == null) {
            return;
        }
        try {
            naming.shutDown();
        } catch (Exception ignore) {
            // 兜底清理本身失败无意义，忽略即可
        }
    }

    /** 应用停机时反注册（优雅下线），避免网关把流量路由到已死实例。 */
    @PreDestroy
    public void deregister() {
        NamingService naming = this.namingService;
        Instance current = this.instance;
        if (naming == null || current == null) {
            return;
        }
        try {
            naming.deregisterInstance(registration.getServiceName(), registration.getGroup(), current);
            naming.shutDown();
            log.info("[Nacos] service deregistered, service={}", registration.getServiceName());
        } catch (Exception e) {
            log.error("nacos service deregister failed, code={}, service={}",
                CODE_DEREGISTER_FAIL, registration.getServiceName(), e);
        }
    }

    /** 包内可见：供单测断言注册失败后未残留 NamingService（字段应为 null，后台线程已 shutDown）。 */
    NamingService currentNamingService() {
        return this.namingService;
    }

    /** 构造临时实例（携带 scheme/contextPath metadata 供网关路由）。 */
    private Instance buildInstance() throws Exception {
        Instance ins = new Instance();
        ins.setIp(resolveIp());
        ins.setPort(registration.getPort());
        ins.setEphemeral(true);
        ins.setHealthy(true);
        Map<String, String> metadata = new HashMap<>();
        if (StringUtils.hasText(registration.getScheme())) {
            metadata.put(META_SCHEME, registration.getScheme());
        }
        if (StringUtils.hasText(registration.getContextPath())) {
            metadata.put(META_CONTEXT_PATH, registration.getContextPath());
        }
        ins.setMetadata(metadata);
        return ins;
    }

    /** 显式配置 IP 优先；否则自动探测本机地址。 */
    private String resolveIp() throws Exception {
        if (StringUtils.hasText(registration.getIp())) {
            return registration.getIp();
        }
        return InetAddress.getLocalHost().getHostAddress();
    }

    private Properties buildProperties() {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, registration.getServerAddr());
        if (StringUtils.hasText(registration.getNamespace())) {
            props.put(PropertyKeyConst.NAMESPACE, registration.getNamespace());
        }
        if (StringUtils.hasText(registration.getUsername())) {
            props.put(PropertyKeyConst.USERNAME, registration.getUsername());
            props.put(PropertyKeyConst.PASSWORD, registration.getPassword());
        }
        return props;
    }
}
