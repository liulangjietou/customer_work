package com.richard.fyoung.customerwork.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NacosRuntimeConfigService} 单测：坏 JSON / 解密失败均不调用 applier（旧配置不被覆盖），
 * 合法配置解密后透传明文密钥给 applier。不依赖真实 Nacos。
 * @author owlzhangfq@gmail.com
 */
class NacosRuntimeConfigServiceTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper mapper = new ObjectMapper();

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

    private CustomerWorkProperties props() {
        CustomerWorkProperties p = new CustomerWorkProperties();
        p.getNacos().setConfigAesKey(KEY);
        return p;
    }

    @Test
    void validConfigDecryptsAndApplies() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        when(applier.apply(any(), eq("sk-plain"), any())).thenReturn(true);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setApiKeyCipher(adminEncrypt("sk-plain", KEY));
        String json = mapper.writeValueAsString(dto);

        assertTrue(service.applyConfig(json));
        verify(applier).apply(any(), eq("sk-plain"), any());
    }

    @Test
    void badJsonDoesNotApply() {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);
        assertFalse(service.applyConfig("{ this is not valid json"));
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void decryptFailureDoesNotApply() throws Exception {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);

        CustomerWorkRuntimeConfig dto = new CustomerWorkRuntimeConfig();
        dto.getModel().setProvider("openai");
        dto.getModel().setName("gpt-4o");
        dto.getModel().setApiKeyCipher("corrupted-cipher-not-base64");
        String json = mapper.writeValueAsString(dto);

        assertFalse(service.applyConfig(json));
        verify(applier, never()).apply(any(), any(), any());
    }

    @Test
    void blankConfigSkipped() {
        RuntimeConfigApplier applier = mock(RuntimeConfigApplier.class);
        NacosRuntimeConfigService service = new NacosRuntimeConfigService(props(), applier);
        assertFalse(service.applyConfig("   "));
        verify(applier, never()).apply(any(), any(), any());
    }
}
