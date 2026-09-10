package com.richard.fyoung.customerwork.tool.mcp.contract;

import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约指纹的规范化。
 *
 * <h3>这一组测试守的是这个能力能不能被信任</h3>
 * <p>MCP 返回的 {@code properties} 是 Map、{@code required} 是 List，两者的顺序<b>在语义上都无关</b>，
 * 而远端实现完全可能每次给出不同顺序。规范化漏一处，漂移检测就会天天报「变了」——
 * 而红的原因与上游的任何改动都无关。本仓库对「每跑必红的门禁」有过明确教训：
 * 几次之后这道门禁就没人信了，那比没有它更糟。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class McpToolContractTest {

    @Test
    @DisplayName("字段顺序不同不算漂移：properties 的 Map 顺序不参与指纹")
    void propertyOrderDoesNotAffectHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("orderNo", schema("string"));
        ordered.put("reason", schema("string"));

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("reason", schema("string"));
        reversed.put("orderNo", schema("string"));

        String a = McpToolContract.of(List.of(tool("refund", "退款", ordered, List.of("orderNo")))).hash();
        String b = McpToolContract.of(List.of(tool("refund", "退款", reversed, List.of("orderNo")))).hash();

        assertEquals(a, b, "字段顺序变了指纹就变，会把远端 map 的迭代顺序当成契约漂移天天报");
    }

    @Test
    @DisplayName("必填项顺序与工具顺序同样不参与指纹")
    void requiredAndToolOrderDoNotAffectHash() {
        McpToolDescriptor refund = tool("refund", "退款", Map.of("a", schema("string"),
            "b", schema("string")), List.of("a", "b"));
        McpToolDescriptor refundReordered = tool("refund", "退款", Map.of("a", schema("string"),
            "b", schema("string")), List.of("b", "a"));
        McpToolDescriptor query = tool("query", "查询", Map.of(), List.of());

        assertEquals(McpToolContract.of(List.of(refund, query)).hash(),
            McpToolContract.of(List.of(query, refundReordered)).hash(),
            "工具顺序或必填项顺序影响了指纹");
    }

    @Test
    @DisplayName("真变了指纹必须变")
    void realChangeChangesHash() {
        String before = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("string")), List.of()))).hash();
        String after = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("number")), List.of()))).hash();

        assertNotEquals(before, after, "字段类型从 string 变成 number 却算出同一个指纹");
    }

    /**
     * 工具描述<b>必须</b>参与指纹。
     *
     * <p>第一版我按「描述不影响调用成败，不该进指纹」写了反过来的断言，跑出来才想清楚：
     * {@code McpContractDrift#between} 有一条「指纹相同直接判无漂移」的快路径，
     * 描述不进指纹的话，只改描述的那次变更连逐工具比对都进不去，
     * <b>{@code DESCRIPTION_CHANGED} 会变成永远报不出来的死代码</b>。</p>
     *
     * <p>描述改了会影响模型选不选这个工具，运营需要看得见——所以它进指纹、进比对，
     * 只是在分级上归入兼容档，不与「工具没了」同级告警。</p>
     */
    @Test
    @DisplayName("工具描述参与指纹，否则描述变更永远报不出来")
    void descriptionAffectsHash() {
        assertNotEquals(
            McpToolContract.of(List.of(tool("refund", "退款", Map.of(), List.of()))).hash(),
            McpToolContract.of(List.of(tool("refund", "发起退款申请", Map.of(), List.of()))).hash(),
            "描述不进指纹，DESCRIPTION_CHANGED 就会被指纹相等的快路径永久拦下");
    }

    /**
     * 但<b>字段级</b>的描述检测不到，这是刻意的取舍。
     *
     * <p>字段只取 schema 片段里的 {@code type} 参与契约，片段里的 description / examples /
     * default 一概不看。整段进来会让 schema 里任何一处文案调整都变成一次漂移事件，
     * 而那些字段多数时候是上游随手补的说明。代价是字段说明改了这里看不见——
     * 真要覆盖它，得先回答「schema 片段里哪些键算契约」，那是独立的一件事。</p>
     */
    @Test
    @DisplayName("字段级 schema 片段里的描述不参与契约")
    void fieldLevelDescriptionIsIgnored() {
        Map<String, Object> before = Map.of("orderNo",
            Map.of("type", "string", "description", "订单号"));
        Map<String, Object> after = Map.of("orderNo",
            Map.of("type", "string", "description", "订单编号，18 位数字"));

        assertEquals(
            McpToolContract.of(List.of(tool("refund", "退款", before, List.of()))).hash(),
            McpToolContract.of(List.of(tool("refund", "退款", after, List.of()))).hash());
    }

    @Test
    @DisplayName("没有 type 的 schema 片段记为 unknown 而不是丢掉")
    void schemaFragmentWithoutTypeIsRecordedAsUnknown() {
        McpToolContract contract = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("weird", Map.of("description", "没写 type")), List.of())));

        assertEquals(McpToolContract.UNKNOWN_TYPE,
            contract.tools().get("refund").properties().get("weird"),
            "取不到 type 就把字段丢掉的话，字段消失与字段没写 type 会混为一谈");
    }

    @Test
    @DisplayName("空契约与无名工具")
    void emptyAndNamelessTools() {
        assertEquals(0, McpToolContract.empty().toolCount());
        // 无名工具进不了契约：它没有稳定标识，比对时既认不出新增也认不出消失
        assertEquals(0, McpToolContract.of(List.of(
            tool(null, "无名", Map.of(), List.of()),
            tool("  ", "空白名", Map.of(), List.of()))).toolCount());
    }

    @Test
    @DisplayName("指纹是 64 位十六进制")
    void hashFormat() {
        assertTrue(McpToolContract.empty().hash().matches("^[0-9a-f]{64}$"));
    }

    private static McpToolDescriptor tool(String name, String description,
                                          Map<String, Object> properties, List<String> required) {
        return new McpToolDescriptor(name, description, "object", properties, required);
    }

    private static Map<String, Object> schema(String type) {
        return Map.of("type", type);
    }
}
