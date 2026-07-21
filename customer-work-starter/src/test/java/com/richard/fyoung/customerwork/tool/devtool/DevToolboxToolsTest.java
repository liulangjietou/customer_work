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
}
