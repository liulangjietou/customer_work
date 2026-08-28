package com.richard.fyoung.customeradmin.common.log;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数脱敏工具单测：apiKey/password/secret/token 关键字字段应被掩码，其余字段保留原值。
 * @author owlzhangfq@gmail.com
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void maskToJson_shouldMaskSensitiveFieldsOnly() {
        Map<String, Object> params = Map.of(
            "username", "admin",
            "password", "admin123",
            "captchaProof", "one-time-login-proof",
            "apiKey", "sk-real-secret-key",
            "modelName", "gpt-4o");

        String json = masker.maskToJson(params);

        assertTrue(json.contains("\"username\":\"admin\""), "非敏感字段应保留原值");
        assertTrue(json.contains("\"modelName\":\"gpt-4o\""));
        assertFalse(json.contains("admin123"), "password 字段值不应出现在输出中");
        assertFalse(json.contains("sk-real-secret-key"), "apiKey 字段值不应出现在输出中");
        assertFalse(json.contains("one-time-login-proof"), "proof 字段值不应出现在输出中");
        assertTrue(json.contains("\"password\":\"******\""));
        assertTrue(json.contains("\"captchaProof\":\"******\""));
        assertTrue(json.contains("\"apiKey\":\"******\""));
    }

    @Test
    void maskToJson_shouldHandleNull() {
        assertNull(masker.maskToJson(null));
    }

    @Test
    void maskToJson_shouldMaskNestedFields() {
        Map<String, Object> nested = Map.of(
            "sessionId", "s1",
            "config", Map.of("token", "secret-token-value", "type", "sse"));

        String json = masker.maskToJson(nested);

        assertFalse(json.contains("secret-token-value"), "嵌套对象里的敏感字段也应被脱敏");
        assertTrue(json.contains("\"type\":\"sse\""));
    }
}
