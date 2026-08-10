package com.richard.fyoung.customerwork.data.skill.storage;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link NacosSkillPublisher} 门控集成测试：本机 Nacos（localhost:8848，nacos/nacos）可达则
 * publish → getConfig 验证 → remove，不可达自动跳过（同仓库既有门控约定）。
 * @author owlzhangfq@gmail.com
 */
class NacosSkillPublisherIntegrationTest {

    private static final String SERVER_ADDR = "localhost:8848";
    private static final String USERNAME = "nacos";
    private static final String PASSWORD = "nacos";

    @Test
    void publishThenGetThenRemove() throws Exception {
        assumeTrue(reachable(), "Nacos 不可达（localhost:8848），跳过该集成测试");

        SkillStorageSettings.Nacos config = new SkillStorageSettings.Nacos();
        config.setServerAddr(SERVER_ADDR);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        String skillCode = "it-skill-" + System.currentTimeMillis();
        String content = "# SKILL\n集成测试内容";
        String dataId = config.getDataIdPrefix() + skillCode + ".md";

        NacosSkillPublisher publisher = new NacosSkillPublisher(config);
        ConfigService verifier = NacosFactory.createConfigService(buildProperties(config));
        try {
            publisher.publish(skillCode, content);
            // publishConfig 返回后到 getConfig 可见有传播延迟，轮询等待再断言
            assertEquals(content, awaitConfig(verifier, dataId, config, content));

            publisher.remove(skillCode);
            assertNull(awaitConfig(verifier, dataId, config, null));
        } finally {
            verifier.shutDown();
        }
    }

    /** 轮询 getConfig 直到读到期望值（或超时返回最后一次读取值），吸收发布/删除的传播延迟。 */
    private String awaitConfig(ConfigService verifier, String dataId, SkillStorageSettings.Nacos config,
                               String expected) throws Exception {
        String last = null;
        for (int i = 0; i < 20; i++) {
            last = verifier.getConfig(dataId, config.getGroup(), config.getTimeoutMs());
            if ((expected == null && last == null) || (expected != null && expected.equals(last))) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

    private Properties buildProperties(SkillStorageSettings.Nacos config) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, config.getServerAddr());
        props.put(PropertyKeyConst.USERNAME, config.getUsername());
        props.put(PropertyKeyConst.PASSWORD, config.getPassword());
        return props;
    }

    private boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 8848), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
