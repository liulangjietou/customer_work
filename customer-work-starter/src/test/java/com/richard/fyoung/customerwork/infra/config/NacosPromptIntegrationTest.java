package com.richard.fyoung.customerwork.infra.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Nacos 配置中心真实集成测试（对接本机 Nacos：http://localhost:8848/nacos，nacos/nacos）。
 *
 * <p>Nacos 不可达时<b>自动跳过</b>（assumeTrue），保证 {@code mvn test} 在无 Nacos 环境仍通过；
 * 本机 Nacos 在线时真实执行：发布一条提示词配置 → {@link NacosPromptService} 拉取生效 → 清理。</p>
 * @author owlzhangfq@gmail.com
 */
class NacosPromptIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 8848;

    @Test
    void publishThenLoad_overRealNacos() throws Exception {
        assumeTrue(SessionPersistenceTestSupport.reachable(HOST, PORT),
            "Nacos 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        String dataId = "customer-work-it-" + UUID.randomUUID();
        String group = "DEFAULT_GROUP";
        String prompt = "集成测试系统提示词-" + UUID.randomUUID();

        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getNacos().setEnabled(true);
        props.getNacos().setServerAddr(HOST + ":" + PORT);
        props.getNacos().setUsername("nacos");
        props.getNacos().setPassword("nacos");
        props.getNacos().setPromptDataId(dataId);
        props.getNacos().setGroup(group);

        Properties nacosProps = new Properties();
        nacosProps.put(PropertyKeyConst.SERVER_ADDR, HOST + ":" + PORT);
        nacosProps.put(PropertyKeyConst.USERNAME, "nacos");
        nacosProps.put(PropertyKeyConst.PASSWORD, "nacos");
        ConfigService configService = NacosFactory.createConfigService(nacosProps);

        try {
            assertTrue(configService.publishConfig(dataId, group, prompt), "发布提示词配置应成功");
            // 发布到生效有短暂延迟，重试读取
            String latest = null;
            for (int i = 0; i < 10 && latest == null; i++) {
                latest = configService.getConfig(dataId, group, 2000);
                if (latest == null || latest.isBlank()) {
                    latest = null;
                    Thread.sleep(300);
                }
            }
            assertTrue(prompt.equals(latest), "应能读回刚发布的提示词");

            // NacosPromptService 绑定后应拉取到该提示词
            NacosPromptService service = new NacosPromptService(props);
            service.bind(configService);
            assertTrue(service.currentPrompt().orElse("").contains("集成测试系统提示词"),
                "NacosPromptService 应加载到 Nacos 下发的提示词");
        } finally {
            configService.removeConfig(dataId, group);
            configService.shutDown();
        }
    }
}
