package com.richard.fyoung.customeradmin.aiconfig.channel;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimeAckIdentity;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 客服机器人运行时配置发布配置：{@code admin.runtime-publish.*}。
 *
 * <p>默认关闭（{@code nacos.enabled=false}）——不影响任何现有后台功能；开启后 admin 在智能体/模型
 * 被改动且命中渠道绑定时，把运行时配置发布到 Nacos，供 8080 客服机器人监听热生效。密文里的 API Key
 * 用 admin 的 {@code ADMIN_AES_SECRET_KEY} 加密后原样携带，8080 侧用同一密钥解密。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.runtime-publish")
public class RuntimePublishProperties {

    private Nacos nacos = new Nacos();
    /** 发布 worker 扫描周期。 */
    private long scanIntervalMs = 5000;
    /** 单次租约时长，必须覆盖模型探测 + Nacos 往返。 */
    private long leaseMs = 60000;
    private int batchSize = 20;
    private int maxAttempts = 8;
    private long baseBackoffMs = 5000;
    private Signing signing = new Signing();
    /** 至少多少个实例 APPLIED 才算整体已应用。 */
    private int minimumAckCount = 1;
    /**
     * ACK 实例身份，单项格式 {@code tenantId|instanceId|token}。每个实例必须使用不同 token，
     * 与客服端 {@code runtime-config-instance-id/runtime-config-ack-token} 一一对应。
     */
    private List<String> ackIdentities = new ArrayList<>();

    public Optional<RuntimeAckIdentity> authenticateAckToken(String actualToken) {
        RuntimeAckIdentity matched = null;
        for (String configured : ackIdentities) {
            Optional<RuntimeAckIdentity> candidate = RuntimeAckIdentity.parse(configured);
            if (candidate.isPresent() && candidate.get().tokenMatches(actualToken)) {
                if (matched != null) {
                    return Optional.empty();
                }
                matched = candidate.get();
            }
        }
        return Optional.ofNullable(matched);
    }

    /**
     * 返回某租户当前声明的实例集合。调用方应在发布任务入队时固化结果，后续配置变更不得改变
     * 已发布 revision 的完成条件。
     */
    public List<String> ackTargetInstanceIds(String tenantId) {
        TreeSet<String> instances = new TreeSet<>();
        for (String configured : ackIdentities) {
            RuntimeAckIdentity.parse(configured)
                .filter(identity -> Objects.equals(identity.tenantId(), tenantId))
                .map(RuntimeAckIdentity::instanceId)
                .ifPresent(instances::add);
        }
        return List.copyOf(instances);
    }

    /** Nacos 配置中心发布目标（与 starter 消费端 {@code customer-work.nacos.*} 对齐）。 */
    @Data
    public static class Nacos {
        private boolean enabled = false;
        private String serverAddr = "localhost:8848";
        private String namespace;
        private String group = "DEFAULT_GROUP";
        /** 运行时配置 dataId（须与 8080 的 {@code customer-work.nacos.runtime-config-data-id} 一致）。 */
        private String dataId = "customer-work-runtime-config";
        private String username;
        private String password;
        private long timeoutMs = 3000;
        /**
         * 服务注册独立子开关（默认 true）：{@code enabled=true} 时是否把 admin 本实例注册进 Nacos 供网关路由。
         * 保持「启用 nacos 即自动注册」的默认；置 false 可「只发布运行时配置、不注册服务」，与注册解耦。
         * 注册器装配条件为 {@code enabled=true 且 registerEnabled≠false}，连接参数复用本 {@code Nacos.*}。
         */
        private boolean registerEnabled = true;
        /**
         * 服务注册对外暴露的实例 IP（供网关 lb:// 路由回连本实例）。留空则自动探测本机地址。
         * 多网卡 / 容器场景可显式指定可达 IP。
         */
        private String instanceIp;
    }

    /** 当前发布签名 key；消费端可在轮换窗口同时配置多个可信 keyId。 */
    @Data
    public static class Signing {
        private boolean enabled = false;
        private String keyId = "";
        private String secret = "";
    }
}
