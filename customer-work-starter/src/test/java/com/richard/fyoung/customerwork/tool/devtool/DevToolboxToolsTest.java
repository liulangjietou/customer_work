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
        JsonNode enc = call(tools.aesEncrypt("hi-明文", key, null, "CBC", null, null, null, null));
        JsonNode dec = call(tools.aesDecrypt(enc.get("ciphertext").asText(), key, null, "CBC",
            null, enc.get("iv").asText(), null, null));
        assertEquals("hi-明文", dec.get("result").asText());
    }

    /** 页面版常用的 hex 组合也要能透过工具壳走通（参数多，壳层漏传某项会直接暴露）。 */
    @Test
    void aes_shouldRoundTripThroughShell_withHexEncodings() throws Exception {
        String key = "000102030405060708090a0b0c0d0e0f";
        String iv = "0f0e0d0c0b0a09080706050403020100";
        JsonNode enc = call(tools.aesEncrypt("ctr-明文", key, "hex", "CTR", null, iv, "hex", "hex"));
        assertEquals("hex", enc.get("outputFormat").asText());
        JsonNode dec = call(tools.aesDecrypt(enc.get("ciphertext").asText(), key, "hex", "CTR", null, iv, "hex", "hex"));
        assertEquals("ctr-明文", dec.get("result").asText());
    }

    @Test
    void aes_shouldConvergeError_onBadKey() throws Exception {
        JsonNode node = call(tools.aesEncrypt("x", "short", null, "CBC", null, null, null, null));
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

    // ---------- hex / JSON 转义 ----------

    @Test
    void hex_shouldRoundTripThroughShell() throws Exception {
        JsonNode enc = call(tools.hexEncode("你好"));
        assertEquals("e4bda0e5a5bd", enc.get("result").asText());
        assertEquals("你好", call(tools.hexDecode("e4bda0e5a5bd")).get("result").asText());
    }

    @Test
    void hexDecode_shouldConvergeError_onOddLength() throws Exception {
        assertTrue(call(tools.hexDecode("abc")).has("error"));
    }

    @Test
    void jsonEscapeUnescape_shouldRoundTripThroughShell() throws Exception {
        String escaped = call(tools.jsonEscape("{\"a\":1}")).get("result").asText();
        assertEquals("{\"a\":1}", call(tools.jsonUnescape(escaped)).get("result").asText());
    }

    @Test
    void jsonUnicodeDecode_shouldRestoreChinese() throws Exception {
        assertEquals("中文", call(tools.jsonUnicodeDecode("\\u4e2d\\u6587")).get("result").asText());
    }

    // ---------- cron / JWT / diff / 格式互转 ----------

    @Test
    void cronExplain_shouldReturnFieldsAndNextTimes() throws Exception {
        JsonNode node = call(tools.cronExplain("0 0 2 * * ?", 2, null));
        assertEquals(6, node.get("fields").size());
        assertEquals(2, node.get("nextTimes").size());
        assertEquals("Asia/Shanghai", node.get("timezone").asText());
    }

    @Test
    void cronExplain_shouldConvergeError_onFiveFieldExpression() throws Exception {
        JsonNode node = call(tools.cronExplain("0 2 * * *", null, null));
        assertTrue(node.has("error"));
        assertTrue(node.get("error").asText().contains("5 段"), "error 应指出段数问题便于自我纠正");
    }

    @Test
    void jwtDecode_shouldExposeClaimsAndVerifyStatus() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        JsonNode node = call(tools.jwtDecode(token, "your-256-bit-secret", null));
        assertEquals("HS256", node.get("algorithm").asText());
        assertEquals("1234567890", node.get("subject").asText());
        assertEquals("VALID", node.get("signatureStatus").asText());
        assertFalse(node.get("expired").asBoolean());
    }

    @Test
    void jwtDecode_shouldConvergeError_onMalformedToken() throws Exception {
        assertTrue(call(tools.jwtDecode("not-a-jwt", null, null)).has("error"));
    }

    @Test
    void textDiff_shouldReportAddedAndDeleted() throws Exception {
        JsonNode node = call(tools.textDiff("a\nb", "a\nc", null, null));
        assertFalse(node.get("identical").asBoolean());
        assertEquals(1, node.get("addedLines").asInt());
        assertEquals(1, node.get("deletedLines").asInt());
    }

    @Test
    void dataConvert_shouldConvertJsonToYaml() throws Exception {
        JsonNode node = call(tools.dataConvert("{\"a\":1}", "json", "yaml", null));
        assertEquals("yaml", node.get("targetFormat").asText());
        assertTrue(node.get("result").asText().contains("a: 1"));
    }

    @Test
    void dataConvert_shouldConvergeError_onUnsupportedFormat() throws Exception {
        assertTrue(call(tools.dataConvert("{\"a\":1}", "json", "toml", null)).has("error"));
    }
}
