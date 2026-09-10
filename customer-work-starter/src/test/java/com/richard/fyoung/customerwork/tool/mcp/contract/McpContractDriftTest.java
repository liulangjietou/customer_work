package com.richard.fyoung.customerwork.tool.mcp.contract;

import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漂移分级。
 *
 * <h3>分级为什么是这个能力的核心</h3>
 * <p>只报「变了」没有用：上游演进是常态，不分级的话运营每次都得自己去读 diff 判断要不要紧，
 * 几次之后就不看了。判据只有一条——<b>模型按旧契约发出的调用，在新契约下还能不能成功</b>。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class McpContractDriftTest {

    private static final McpToolContract BASELINE = McpToolContract.of(List.of(
        tool("refund", "退款", Map.of("orderNo", schema("string"), "note", schema("string")),
            List.of("orderNo")),
        tool("query", "查询", Map.of("id", schema("string")), List.of("id"))));

    @Test
    @DisplayName("首次采集只建立基线，不算漂移")
    void firstCaptureIsNotDrift() {
        McpContractDrift drift = McpContractDrift.between(null, BASELINE);

        assertEquals(McpDriftSeverity.NONE, drift.severity());
        assertFalse(drift.hasDrift());
        assertEquals(BASELINE.hash(), drift.currentHash());
    }

    @Test
    @DisplayName("契约没变就是没变")
    void identicalContractHasNoDrift() {
        assertFalse(McpContractDrift.between(BASELINE, BASELINE).hasDrift());
    }

    @Test
    @DisplayName("工具下线是 BREAKING：模型仍会尝试调用它")
    void removedToolIsBreaking() {
        McpToolContract current = McpToolContract.of(List.of(
            tool("query", "查询", Map.of("id", schema("string")), List.of("id"))));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertTrue(drift.isBreaking());
        assertTrue(hasChange(drift, "refund", McpChangeType.TOOL_REMOVED));
    }

    @Test
    @DisplayName("新增必填字段是 BREAKING：旧调用不带它就失败")
    void newRequiredFieldIsBreaking() {
        McpToolContract current = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("string"), "note", schema("string")),
                List.of("orderNo", "note")),
            tool("query", "查询", Map.of("id", schema("string")), List.of("id"))));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertTrue(drift.isBreaking());
        assertTrue(hasChange(drift, "refund", McpChangeType.REQUIRED_ADDED));
    }

    @Test
    @DisplayName("字段类型改变是 BREAKING")
    void fieldTypeChangeIsBreaking() {
        McpToolContract current = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("number"), "note", schema("string")),
                List.of("orderNo")),
            tool("query", "查询", Map.of("id", schema("string")), List.of("id"))));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertTrue(drift.isBreaking());
        assertTrue(hasChange(drift, "refund", McpChangeType.FIELD_TYPE_CHANGED));
    }

    /**
     * 新增工具、新增可选字段、必填放宽、描述修改——四种都不会让旧调用失败。
     *
     * <p>放在一条里是因为它们要一起验证同一件事：<b>整体级别取所有变更里最严重的那个</b>，
     * 而这四种同时出现时仍然只能是 COMPATIBLE。逐条分开测的话，
     * 「一堆兼容变更凑在一起会不会被误判成 BREAKING」这个问题反而验不到。</p>
     */
    @Test
    @DisplayName("新增工具/可选字段、必填放宽、改描述都是 COMPATIBLE")
    void additiveChangesAreCompatible() {
        McpToolContract current = McpToolContract.of(List.of(
            tool("refund", "发起退款申请",
                Map.of("orderNo", schema("string"), "note", schema("string"),
                    "channel", schema("string")),
                List.of()),
            tool("query", "查询", Map.of("id", schema("string")), List.of("id")),
            tool("cancel", "取消", Map.of(), List.of())));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertEquals(McpDriftSeverity.COMPATIBLE, drift.severity(),
            "一堆兼容变更凑在一起被误判成 BREAKING：" + drift.changes());
        assertTrue(hasChange(drift, "cancel", McpChangeType.TOOL_ADDED));
        assertTrue(hasChange(drift, "refund", McpChangeType.FIELD_ADDED));
        assertTrue(hasChange(drift, "refund", McpChangeType.REQUIRED_REMOVED));
        assertTrue(hasChange(drift, "refund", McpChangeType.DESCRIPTION_CHANGED));
    }

    @Test
    @DisplayName("兼容与破坏混在一起时，整体取最严重的")
    void mixedChangesTakeWorstSeverity() {
        McpToolContract current = McpToolContract.of(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("string")), List.of("orderNo")),
            tool("query", "查询", Map.of("id", schema("string")), List.of("id")),
            tool("cancel", "取消", Map.of(), List.of())));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertEquals(McpDriftSeverity.BREAKING, drift.severity(),
            "新增了一个工具就把「note 字段消失」这条盖过去了——失败率被静默抹平是本仓库踩过的形状");
        assertTrue(hasChange(drift, "cancel", McpChangeType.TOOL_ADDED));
        assertTrue(hasChange(drift, "refund", McpChangeType.FIELD_REMOVED));
    }

    @Test
    @DisplayName("入参 schema 顶层类型改变是 BREAKING")
    void schemaTypeChangeIsBreaking() {
        McpToolContract current = McpToolContract.of(List.of(
            new McpToolDescriptor("refund", "退款", "array",
                Map.of("orderNo", schema("string"), "note", schema("string")), List.of("orderNo")),
            tool("query", "查询", Map.of("id", schema("string")), List.of("id"))));

        McpContractDrift drift = McpContractDrift.between(BASELINE, current);

        assertTrue(drift.isBreaking());
        assertTrue(hasChange(drift, "refund", McpChangeType.SCHEMA_TYPE_CHANGED));
    }

    /** 每一种变更类型都必须有明确的分级立场，新增枚举值时这里会提醒补测试。 */
    @Test
    @DisplayName("破坏性分类清单")
    void breakingClassification() {
        assertTrue(McpChangeType.TOOL_REMOVED.isBreaking());
        assertTrue(McpChangeType.REQUIRED_ADDED.isBreaking());
        assertTrue(McpChangeType.FIELD_REMOVED.isBreaking());
        assertTrue(McpChangeType.FIELD_TYPE_CHANGED.isBreaking());
        assertTrue(McpChangeType.SCHEMA_TYPE_CHANGED.isBreaking());

        assertFalse(McpChangeType.TOOL_ADDED.isBreaking());
        assertFalse(McpChangeType.FIELD_ADDED.isBreaking());
        assertFalse(McpChangeType.REQUIRED_REMOVED.isBreaking());
        assertFalse(McpChangeType.DESCRIPTION_CHANGED.isBreaking());

        assertEquals(9, McpChangeType.values().length,
            "新增了变更类型：先回答它会不会让按旧契约发出的调用失败，再来这里补一条");
    }

    private static boolean hasChange(McpContractDrift drift, String toolName, McpChangeType type) {
        return drift.changes().stream()
            .anyMatch(c -> c.toolName().equals(toolName) && c.type() == type);
    }

    private static McpToolDescriptor tool(String name, String description,
                                          Map<String, Object> properties, List<String> required) {
        return new McpToolDescriptor(name, description, "object", properties, required);
    }

    private static Map<String, Object> schema(String type) {
        return Map.of("type", type);
    }
}
