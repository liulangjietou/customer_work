package com.richard.fyoung.customerwork.infra.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时配置热更新 Nacos 真实集成测试（对接本机 Nacos：localhost:8848，nacos/nacos）。
 *
 * <p>Nacos 不可达时<b>自动跳过</b>；在线时真实执行：发布一份带 AES 加密密钥的运行时配置 JSON →
 * {@link NacosRuntimeConfigService#bind} 拉取解密并调用 applier（此处 mock，验证密钥被正确解密透传）→ 清理。</p>
 * @author owlzhangfq@gmail.com
 */
class NacosRuntimeConfigIntegrationTest {

    private static final String HOST = "localhost";
    private static final int PORT = 8848;
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static String adminEncrypt(String plain, String key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
            new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    @Test
    void publishThenApply_overRealNacos() throws Exception {
        assumeTrue(SessionPersistenceTestSupport.reachable(HOST, PORT),
            "Nacos 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        String dataId = "customer-work-rt-it-" + UUID.randomUUID();
        String group = "DEFAULT_GROUP";

        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getNacos().setRuntimeConfigEnabled(true);
        props.getNacos().setServerAddr(HOST + ":" + PORT);
        props.getNacos().setUsername("nacos");
        props.getNacos().setPassword("nacos");
        props.getNacos().setRuntimeConfigDataId(dataId);
        props.getNacos().setGroup(group);
        props.getNacos().setConfigAesKey(KEY);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setApiKeyCipher(adminEncrypt("sk-it-plain", KEY));
        String json = new ObjectMapper().writeValueAsString(dto);

        Properties nacosProps = new Properties();
        nacosProps.put(PropertyKeyConst.SERVER_ADDR, HOST + ":" + PORT);
        nacosProps.put(PropertyKeyConst.USERNAME, "nacos");
        nacosProps.put(PropertyKeyConst.PASSWORD, "nacos");
        ConfigService configService = NacosFactory.createConfigService(nacosProps);

        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), eq("sk-it-plain"), any())).thenReturn(true);

        try {
            configService.publishConfig(dataId, group, json);
            // 等发布生效后再 bind
            for (int i = 0; i < 10; i++) {
                String latest = configService.getConfig(dataId, group, 2000);
                if (latest != null && !latest.isBlank()) {
                    break;
                }
                Thread.sleep(300);
            }
            NacosRuntimeConfigService service = new NacosRuntimeConfigService(props, applier);
            service.bind(configService);
            verify(applier, timeout(3000)).apply(any(), eq("sk-it-plain"), any());
        } finally {
            configService.removeConfig(dataId, group);
            configService.shutDown();
        }
    }
}
