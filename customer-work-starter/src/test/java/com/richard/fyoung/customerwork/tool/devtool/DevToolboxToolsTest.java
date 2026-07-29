package com.richard.fyoung.customerwork.tool.devtool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevToolboxTools} 单测：@Tool 壳的错误收敛（坏参数返回 error JSON 而非抛异常）、
 * 正常路径序列化、AES 走通、以及超长结果截断保护。
 * @author owlzhangfq@gmail.com
 */
class DevToolboxToolsTest {

    /** openssl 自签 EC 证书与配对私钥（仅测试用，不含任何真实业务身份）。 */
    private static final String SELF_SIGNED_CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIIBejCCAR+gAwIBAgIUFcpIIxy3qTLcJS6U/aAD+TestxUwCgYIKoZIzj0EAwIw
        EjEQMA4GA1UEAwwHZWMudGVzdDAeFw0yNjA3MjkwODQyMDZaFw0yNjA4MjgwODQy
        MDZaMBIxEDAOBgNVBAMMB2VjLnRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC
        AAQ2G7NAgGxzsqB8yJKX71LZw65WTiNqUqtqQvtUMCLHdQPKv8r93X9Q0u+mAfS4
        6DaElMfGKNC+HACAz8C7JaMRo1MwUTAdBgNVHQ4EFgQUotIrb6QtJz8N30RDQNpw
        fk/QIZUwHwYDVR0jBBgwFoAUotIrb6QtJz8N30RDQNpwfk/QIZUwDwYDVR0TAQH/
        BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEAiXk3m+WC/z8jOoYcYvkTmVv0i0D9
        DbfD8PaiSskPitUCIQDznPdksj09BkliLIjma0Rpp+GPF50VcWniVLMUQ+L+nA==
        -----END CERTIFICATE-----
        """;

    private static final String SEC1_EC_KEY_PEM = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEILmEvcRueU5ts448aMjU+9+A/UGiC4n7IU4y6u/DDndmoAoGCCqGSM49
        AwEHoUQDQgAENhuzQIBsc7KgfMiSl+9S2cOuVk4jalKrakL7VDAix3UDyr/K/d1/
        UNLvpgH0uOg2hJTHxijQvhwAgM/AuyWjEQ==
        -----END EC PRIVATE KEY-----
        """;

    private final DevToolboxTools tools = new DevToolboxTools();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(reactor.core.publisher.Mono<String> mono) throws Exception {
        return mapper.readTree(mono.block());
    }

    @Test
    void jsonFormat_shouldReturnFormatted_onValidInput() throws Exception {
        // 工具输出把格式化文本作为 JSON 字符串字面量回传（外层是 TextNode），取其文本再解析即为原对象
        String raw = tools.jsonFormat("{\"a\":1}", 2).block();
        String formatted = mapper.readTree(raw).asText();
        assertTrue(formatted.contains("\n"), "应为多行格式化文本");
        assertEquals(1, mapper.readTree(formatted).get("a").asInt());
    }

    @Test
    void jsonFormat_shouldConvergeError_onBadJson() throws Exception {
        JsonNode node = call(tools.jsonFormat("{bad", 2));
        assertTrue(node.has("error"), "坏 JSON 应收敛为 error 字段而非抛异常");
        assertTrue(node.get("error").asText().contains("line"), "error 应带行列号便于自我纠正");
    }

    @Test
    void jsonValidate_shouldReportInvalid() throws Exception {
        JsonNode node = call(tools.jsonValidate("{\"a\": }"));
        assertFalse(node.get("valid").asBoolean());
        assertTrue(node.get("line").asInt() >= 1);
    }

    @Test
    void textHash_shouldReturnResult() throws Exception {
        JsonNode node = call(tools.textHash("MD5", "abc", null));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", node.get("result").asText());
    }

    @Test
    void aes_shouldRoundTripThroughShell() throws Exception {
        String key = "1234567890123456";
        JsonNode enc = call(tools.aesEncrypt("hi-明文", key, "CBC", null));
        JsonNode dec = call(tools.aesDecrypt(enc.get("ciphertext").asText(), key, "CBC", enc.get("iv").asText()));
        assertEquals("hi-明文", dec.get("result").asText());
    }

    @Test
    void aes_shouldConvergeError_onBadKey() throws Exception {
        JsonNode node = call(tools.aesEncrypt("x", "short", "CBC", null));
        assertTrue(node.has("error"));
        assertFalse(node.has("ciphertext"));
    }

    @Test
    void uuidGenerate_shouldDefaultToOne_whenCountNull() throws Exception {
        JsonNode node = call(tools.uuidGenerate(null));
        assertEquals(1, node.get("result").size());
    }

    @Test
    void largeResult_shouldBeTruncated() throws Exception {
        // 构造一段长文本，base64 编码后包裹进 {"result":...} 会超过 8000 字符阈值
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7000; i++) {
            sb.append('a');
        }
        JsonNode node = call(tools.base64Encode(sb.toString()));
        assertTrue(node.get("truncated").asBoolean(), "超长结果应标注 truncated");
        assertTrue(node.get("originalLength").asInt() > 8000, "应记录原始长度");
    }

    // ---------- 证书 ----------

    @Test
    void certParse_shouldReturnCertificateFields_onValidPem() throws Exception {
        JsonNode node = call(tools.certParse(SELF_SIGNED_CERT_PEM));
        assertTrue(node.has("certificates"), "应返回 certificates 数组");
        assertEquals(1, node.get("certificates").size());
        JsonNode cert = node.get("certificates").get(0);
        assertTrue(cert.get("subject").asText().contains("ec.test"));
        assertTrue(cert.has("expired") && cert.has("daysRemaining"), "过期判断字段必须直出，不让 LLM 自己算时间戳");
        assertTrue(cert.get("sha256Fingerprint").asText().contains(":"));
    }

    @Test
    void certParse_shouldConvergeError_onGarbageInput() throws Exception {
        JsonNode node = call(tools.certParse("not a pem"));
        assertTrue(node.has("error"), "无法识别的输入应收敛为 error 字段而非抛异常");
        assertTrue(node.get("error").asText().contains("CERTIFICATE"), "error 应提示期望的 PEM 格式便于自我纠正");
    }

    @Test
    void certMatch_shouldReportMatched_forPairedKey() throws Exception {
        JsonNode node = call(tools.certMatch(SELF_SIGNED_CERT_PEM, SEC1_EC_KEY_PEM));
        assertTrue(node.get("matched").asBoolean(), "配对的证书与私钥应返回 matched=true");
        assertEquals("EC", node.get("publicKeyAlgorithm").asText());
    }

    @Test
    void certMatch_shouldConvergeError_onBadPrivateKey() throws Exception {
        JsonNode node = call(tools.certMatch(SELF_SIGNED_CERT_PEM, "garbage"));
        assertTrue(node.has("error"));
    }
}
