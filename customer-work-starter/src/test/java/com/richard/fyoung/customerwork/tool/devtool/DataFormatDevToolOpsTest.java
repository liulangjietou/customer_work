package com.richard.fyoung.customerwork.tool.devtool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataFormatDevToolOps} 单测：三种格式互转、根元素名校验、XXE 防护与输入边界。
 * @author owlzhangfq@gmail.com
 */
class DataFormatDevToolOpsTest {

    private final DataFormatDevToolOps ops = new DataFormatDevToolOps();

    @Test
    void convert_jsonToYaml() {
        String yaml = ops.convert("{\"name\":\"张三\",\"age\":30}", "json", "yaml", null).getResult();
        assertTrue(yaml.contains("name: \"张三\"") || yaml.contains("name: 张三"), "实际输出：" + yaml);
        assertTrue(yaml.contains("age: 30"));
        assertFalseStartsWithDocMarker(yaml);
    }

    @Test
    void convert_yamlToJson() {
        String json = ops.convert("name: 张三\nage: 30\n", "yaml", "json", null).getResult();
        assertTrue(json.contains("\"name\" : \"张三\""), "实际输出：" + json);
        assertTrue(json.contains("\"age\" : 30"));
    }

    @Test
    void convert_shouldRoundTripJsonYamlJson() {
        String origin = "{\"a\":1,\"b\":{\"c\":[1,2,3]}}";
        String yaml = ops.convert(origin, "json", "yaml", null).getResult();
        String back = ops.convert(yaml, "yaml", "json", null).getResult();
        // 去掉美化空白后应与原始语义一致
        assertEquals(origin.replaceAll("\\s", ""), back.replaceAll("\\s", ""));
    }

    @Test
    void convert_jsonToXml_shouldUseGivenRootName() {
        String xml = ops.convert("{\"id\":1,\"name\":\"x\"}", "json", "xml", "order").getResult();
        assertTrue(xml.startsWith("<order>"), "实际输出：" + xml);
        assertTrue(xml.contains("<id>1</id>"));
    }

    @Test
    void convert_jsonToXml_shouldDefaultRootName() {
        assertTrue(ops.convert("{\"a\":1}", "json", "xml", null).getResult().startsWith("<root>"));
    }

    @Test
    void convert_xmlToJson() {
        String json = ops.convert("<order><id>1</id><name>x</name></order>", "xml", "json", null).getResult();
        // XML 无类型系统，数字也会转成字符串，这是已知语义损耗
        assertTrue(json.contains("\"id\" : \"1\""), "实际输出：" + json);
        assertTrue(json.contains("\"name\" : \"x\""));
    }

    @Test
    void convert_shouldReportFormatsInResult() {
        DataFormatDevToolOps.ConvertResult result = ops.convert("{\"a\":1}", "JSON", "YAML", null);
        assertEquals("json", result.getSourceFormat());
        assertEquals("yaml", result.getTargetFormat());
    }

    /** 数组做 XML 根元素没有合法表示，必须明确拦下而不是产出结构诡异的 XML。 */
    @Test
    void convert_shouldRejectArrayAsXmlRoot() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.convert("[1,2,3]", "json", "xml", null));
        assertTrue(ex.getMessage().contains("数组"));
    }

    @Test
    void convert_shouldRejectIllegalRootName() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("{\"a\":1}", "json", "xml", "1-bad name"));
    }

    /**
     * XXE 防护：输入完全来自用户与模型，属不可信输入。禁用 DTD 后带 DOCTYPE 的文档会直接解析失败，
     * 外部实体自然无从展开——这里断言"解析被拒绝"，而不是断言"实体没被替换"。
     */
    @Test
    void convert_shouldRejectXmlWithDoctype_preventingXxe() {
        String xxe = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<foo>&xxe;</foo>";
        assertThrows(IllegalArgumentException.class, () -> ops.convert(xxe, "xml", "json", null));
    }

    @Test
    void convert_shouldRejectUnsupportedFormat() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("{\"a\":1}", "json", "toml", null));
        assertThrows(IllegalArgumentException.class, () -> ops.convert("{\"a\":1}", "csv", "json", null));
    }

    @Test
    void convert_shouldReportParseFailureWithSourceFormat() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.convert("{not json", "json", "yaml", null));
        assertTrue(ex.getMessage().contains("JSON"));
    }

    @Test
    void convert_shouldRejectBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> ops.convert("  ", "json", "yaml", null));
    }

    @Test
    void convert_shouldRejectOversizedContent() {
        String huge = "a".repeat(512 * 1024 + 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.convert(huge, "json", "yaml", null));
        assertTrue(ex.getMessage().contains("上限"));
    }

    /** YAML 输出不带文档起始符，与主流在线工具观感一致。 */
    private void assertFalseStartsWithDocMarker(String yaml) {
        assertTrue(!yaml.startsWith("---"), "YAML 输出不应带文档起始符，实际：" + yaml);
    }
}
