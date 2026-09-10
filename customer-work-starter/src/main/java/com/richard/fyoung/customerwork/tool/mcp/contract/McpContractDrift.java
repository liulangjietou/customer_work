package com.richard.fyoung.customerwork.tool.mcp.contract;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两份 MCP 工具契约之间的差异。
 *
 * <h3>为什么按「整体快照 + diff」而不是「一个工具一行」</h3>
 * <p>漂移这件事的主语是<b>整个服务器</b>：工具消失与工具新增同样重要，而按工具存行的话，
 * 「消失」只能靠「这次没更新 last_seen」去间接推断——用时间戳当信号是本仓库反复踩过的形状
 * （评测取基线、CSAT 分区都栽过）。整体快照比对没有这个含糊：不在新契约里就是消失了。</p>
 *
 * <h3>它不阻断任何事</h3>
 * <p>检测到 {@link McpDriftSeverity#BREAKING} 也只是报告与告警，<b>不会自动停用这个 MCP</b>。
 * 上游演进是常态，为一次漂移把业务能力整个关掉，代价远大于收益；
 * 而且判定依据是我们这边的基线，基线本身可能已经过期。要不要停由人决定。</p>
 *
 * @param severity  总体级别
 * @param changes   逐条变更（按工具名、再按类型稳定排序，便于 diff 展示与回归断言）
 * @param baselineHash 比对基线的指纹
 * @param currentHash  当前契约的指纹
 * @author owlzhangfq@gmail.com
 */
public record McpContractDrift(McpDriftSeverity severity,
                               List<McpToolChange> changes,
                               String baselineHash,
                               String currentHash) {

    /** 有无变更。 */
    public boolean hasDrift() {
        return severity != McpDriftSeverity.NONE;
    }

    /** 是否存在会让既有调用失败的变更。 */
    public boolean isBreaking() {
        return severity == McpDriftSeverity.BREAKING;
    }

    /**
     * 比对两份契约。
     *
     * <p><b>指纹相同直接判无漂移</b>，不再逐工具比——那既省事又保证「指纹是契约的完整代表」
     * 这个前提始终成立：如果指纹相同却比出差异，说明规范化漏了某个维度，那是 bug 而不是漂移。</p>
     *
     * @param baseline 基线契约；为 {@code null} 视为首次采集（无漂移，只是建立基线）
     * @param current  当前契约
     */
    public static McpContractDrift between(McpToolContract baseline, McpToolContract current) {
        Objects.requireNonNull(current, "current");
        if (baseline == null) {
            return new McpContractDrift(McpDriftSeverity.NONE, List.of(), null, current.hash());
        }
        if (Objects.equals(baseline.hash(), current.hash())) {
            return new McpContractDrift(McpDriftSeverity.NONE, List.of(),
                baseline.hash(), current.hash());
        }

        List<McpToolChange> changes = new ArrayList<>();
        Set<String> allTools = new TreeSet<>(baseline.tools().keySet());
        allTools.addAll(current.tools().keySet());

        for (String toolName : allTools) {
            McpToolContract.ToolView before = baseline.tools().get(toolName);
            McpToolContract.ToolView after = current.tools().get(toolName);
            if (before == null) {
                changes.add(McpToolChange.of(toolName, McpChangeType.TOOL_ADDED, "新增工具"));
            } else if (after == null) {
                changes.add(McpToolChange.of(toolName, McpChangeType.TOOL_REMOVED, "工具已下线"));
            } else {
                compareTool(toolName, before, after, changes);
            }
        }

        boolean breaking = changes.stream().anyMatch(c -> c.type().isBreaking());
        McpDriftSeverity severity = breaking ? McpDriftSeverity.BREAKING : McpDriftSeverity.COMPATIBLE;
        return new McpContractDrift(severity, List.copyOf(changes), baseline.hash(), current.hash());
    }

    private static void compareTool(String toolName,
                                    McpToolContract.ToolView before,
                                    McpToolContract.ToolView after,
                                    List<McpToolChange> changes) {
        if (!Objects.equals(before.schemaType(), after.schemaType())) {
            changes.add(McpToolChange.of(toolName, McpChangeType.SCHEMA_TYPE_CHANGED,
                before.schemaType() + " → " + after.schemaType()));
        }
        if (!Objects.equals(before.description(), after.description())) {
            changes.add(McpToolChange.of(toolName, McpChangeType.DESCRIPTION_CHANGED, "描述已修改"));
        }

        Map<String, String> beforeProps = before.properties();
        Map<String, String> afterProps = after.properties();
        Set<String> fields = new TreeSet<>(beforeProps.keySet());
        fields.addAll(afterProps.keySet());
        for (String field : fields) {
            String beforeType = beforeProps.get(field);
            String afterType = afterProps.get(field);
            if (beforeType == null) {
                changes.add(McpToolChange.of(toolName, McpChangeType.FIELD_ADDED,
                    field + " (" + afterType + ")"));
            } else if (afterType == null) {
                changes.add(McpToolChange.of(toolName, McpChangeType.FIELD_REMOVED,
                    field + " (原 " + beforeType + ")"));
            } else if (!beforeType.equals(afterType)) {
                changes.add(McpToolChange.of(toolName, McpChangeType.FIELD_TYPE_CHANGED,
                    field + ": " + beforeType + " → " + afterType));
            }
        }

        Set<String> beforeRequired = new LinkedHashSet<>(before.required());
        Set<String> afterRequired = new LinkedHashSet<>(after.required());
        for (String field : new TreeSet<>(afterRequired)) {
            if (!beforeRequired.contains(field)) {
                changes.add(McpToolChange.of(toolName, McpChangeType.REQUIRED_ADDED, field));
            }
        }
        for (String field : new TreeSet<>(beforeRequired)) {
            if (!afterRequired.contains(field)) {
                changes.add(McpToolChange.of(toolName, McpChangeType.REQUIRED_REMOVED, field));
            }
        }
    }
}
