package com.richard.fyoung.customerwork.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nacos 服务注册真实集成测试（对接本机 Nacos：localhost:8848，nacos/nacos）。
 *
 * <p>Nacos 不可达时<b>自动跳过</b>（assumeTrue），与既有 {@code NacosPromptIntegrationTest} 门控一致。
 * 在线时真实执行：{@link NacosServiceRegistrar} 注册临时实例 → 查 Nacos NamingService 断言实例出现 →
 * 反注册 → 断言实例消失。另含一条<b>永远执行</b>的 fail-safe 用例：Nacos 不可达时注册/反注册不抛出、不阻断。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class NacosServiceRegistrarIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 8848;
    private static final String NAMESPACE = "customer-work";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String IP = "127.0.0.1";

    @Test
    void registerThenDeregister_overRealNacos() throws Exception {
        assumeTrue(SessionPersistenceTestSupport.reachable(HOST, PORT),
            "Nacos 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        String serviceName = "it-registrar-" + UUID.randomUUID();
        int servicePort = 18080;

        NacosRegistration registration = NacosRegistration.builder()
            .serverAddr(HOST + ":" + PORT)
            .namespace(NAMESPACE)
            .group(GROUP)
            .username("nacos")
            .password("nacos")
            .serviceName(serviceName)
            .ip(IP)
            .port(servicePort)
            .scheme("http")
            .contextPath("")
            .build();

        NacosServiceRegistrar registrar = new NacosServiceRegistrar(registration);
        NamingService query = NacosFactory.createNamingService(buildQueryProps());
        try {
            registrar.register();
            assertTrue(waitForInstanceCount(query, serviceName, 1),
                "注册后应能在 Nacos 查到该实例");
            Instance found = query.getAllInstances(serviceName, GROUP).get(0);
            assertEquals(IP, found.getIp(), "实例 IP 应一致");
            assertEquals(servicePort, found.getPort(), "实例端口应一致");
            assertEquals("http", found.getMetadata().get("scheme"), "metadata scheme 应写入");

            registrar.deregister();
            assertTrue(waitForInstanceCount(query, serviceName, 0),
                "反注册后该实例应从 Nacos 消失");
        } finally {
            query.shutDown();
        }
    }

    /** fail-safe：Nacos 不可达时注册与反注册均不抛出、不阻断（永远执行，不门控）。 */
    @Test
    void register_whenNacosUnreachable_doesNotThrow() {
        NacosRegistration registration = NacosRegistration.builder()
            .serverAddr("127.0.0.1:1")   // 无监听端口，模拟不可达
            .namespace(NAMESPACE)
            .group(GROUP)
            .serviceName("it-failsafe-" + UUID.randomUUID())
            .ip(IP)
            .port(18081)
            .scheme("http")
            .contextPath("")
            .build();

        NacosServiceRegistrar registrar = new NacosServiceRegistrar(registration);
        assertDoesNotThrow(registrar::register, "Nacos 不可达注册不应抛出");
        // 注册失败后不得残留 NamingService（已 shutDown、后台重连线程已释放，字段保持 null）
        assertNull(registrar.currentNamingService(),
            "注册失败后应无残留 NamingService（后台重连线程已释放）");
        assertDoesNotThrow(registrar::deregister, "反注册不应抛出");
    }

    /** 轮询等待实例数达到期望值（注册/反注册在 Nacos 侧有短暂最终一致延迟）。 */
    private boolean waitForInstanceCount(NamingService query, String serviceName, int expected) throws Exception {
        for (int i = 0; i < 20; i++) {
            List<Instance> instances = query.getAllInstances(serviceName, GROUP);
            if (instances.size() == expected) {
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    private Properties buildQueryProps() {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, HOST + ":" + PORT);
        props.put(PropertyKeyConst.NAMESPACE, NAMESPACE);
        props.put(PropertyKeyConst.USERNAME, "nacos");
        props.put(PropertyKeyConst.PASSWORD, "nacos");
        return props;
    }
}
