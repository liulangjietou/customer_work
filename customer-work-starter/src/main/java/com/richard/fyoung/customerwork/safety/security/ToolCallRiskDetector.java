package com.richard.fyoung.customerwork.safety.security;

import io.agentscope.core.message.ToolUseBlock;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 工具调用风险判定核心（纯判定 + 纯改写，无 Spring 依赖、无副作用，不落库不发事件）：
 * 把"哪些工具调用算高风险"的规则算法收拢到唯一一处，供各业务模块的护栏中间件 / 人工确认闭环 /
 * 只读模式闸门复用，规则由 {@link ToolCallRiskRules} 从各自的配置源绑入。
 *
 * <p>能力分三类：</p>
 * <ul>
 *   <li><b>判定</b>：{@link #matchesDestructive(String)}（破坏性命令子集，护栏用）、
 *       {@link #assess(List)} / {@link #assess(ToolUseBlock)}（需人工确认的高风险清单）、
 *       {@link #isMutatingTool(ToolUseBlock)}（只读模式下需拦改的写副作用工具）；</li>
 *   <li><b>归类</b>：{@link #isWriteToolName(String)}、{@link #describeTool(ToolUseBlock)}；</li>
 *   <li><b>改写</b>：{@link #neutralize(ToolUseBlock, String)}（按风险正则精确改写）、
 *       {@link #neutralizeAllStringParams(ToolUseBlock, String)}（整体取消）。</li>
 * </ul>
 *
 * <p>{@code ToolUseBlock} 不带来源标识，删除类 / 写类工具只能按工具名关键字启发式归类；启发式识别不到的
 * 写副作用工具由 {@link ToolCallRiskRules#mutatingToolPatterns()} 黑名单兜底。</p>
 * @author owlzhangfq@gmail.com
 */
public class ToolCallRiskDetector {

    /** 工具名分类关键字（{@code ToolUseBlock} 不带来源标识，只能按名字启发式归类）。 */
    private static final String[] DELETE_TOOL_KEYWORDS = {"delete", "remove", "unlink"};
    private static final String[] WRITE_TOOL_KEYWORDS = {"write", "edit", "save", "create", "replace", "patch", "insert"};

    /** target 展示截断长度，避免超长命令撑爆展示卡片。 */
    private static final int TARGET_MAX_LEN = 200;

    private static final String REASON_DELETE = "删除文件";
    private static final String REASON_DESTRUCTIVE_COMMAND = "执行破坏性命令";
    private static final String REASON_NON_READONLY_COMMAND = "执行非只读命令";
    private static final String REASON_MODIFY_DEPENDENCY = "修改依赖/构建文件";

    private final List<Pattern> destructivePatterns;
    private final List<Pattern> confirmableCommandPatterns;
    private final List<Pattern> dependencyFilePatterns;
    private final int batchModifyThreshold;
    private final String[] execToolKeywords;
    private final List<Pattern> readonlyToolPatterns;
    private final List<Pattern> mutatingToolPatterns;

    /** 规则在构造期一次性编译并定格；配置需要热更新时由调用方重建本判定器。 */
    public ToolCallRiskDetector(ToolCallRiskRules rules) {
        this.destructivePatterns = compile(rules.destructivePatterns());
        this.confirmableCommandPatterns = compile(rules.confirmableCommandPatterns());
        this.dependencyFilePatterns = compile(rules.dependencyFilePatterns());
        this.batchModifyThreshold = rules.batchModifyThreshold();
        this.execToolKeywords = toLowerArray(rules.execToolKeywords());
        this.readonlyToolPatterns = compile(rules.readonlyToolPatterns());
        this.mutatingToolPatterns = compile(rules.mutatingToolPatterns());
    }

    /** 字符串是否命中破坏性命令正则（护栏"直接改写"那一档的判定，与高风险清单同源）。 */
    public boolean matchesDestructive(String text) {
        return matchesAny(destructivePatterns, text);
    }

    /**
     * 评估一批工具调用（一个 acting 步）的高风险清单：每个高风险工具调用产出至多一条 {@link ToolCallRisk}，
     * 另按"单轮写文件工具数超阈值"补一条 {@link ToolCallRiskType#BATCH_MODIFY}。返回空表示本步无风险。
     */
    public List<ToolCallRisk> assess(List<ToolUseBlock> toolCalls) {
        List<ToolCallRisk> risks = new ArrayList<>();
        if (CollectionUtils.isEmpty(toolCalls)) {
            return risks;
        }
        int writeToolCount = 0;
        for (ToolUseBlock use : toolCalls) {
            assess(use).ifPresent(risks::add);
            if (isWriteTool(use.getName())) {
                writeToolCount++;
            }
        }
        if (writeToolCount > batchModifyThreshold) {
            risks.add(new ToolCallRisk(ToolCallRiskType.BATCH_MODIFY, writeToolCount + " files",
                "单轮批量修改 " + writeToolCount + " 个文件（超过阈值 " + batchModifyThreshold + "）"));
        }
        return risks;
    }

    /**
     * 单个工具调用的风险判定（按严重度短路取第一条）：删除文件 &gt; 破坏性命令 &gt; 非只读命令 &gt; 改依赖。
     */
    public Optional<ToolCallRisk> assess(ToolUseBlock use) {
        if (use == null) {
            return Optional.empty();
        }
        Map<String, Object> input = use.getInput();
        String toolName = use.getName();
        // 1) 删除文件：删除类工具
        if (isDeleteTool(toolName)) {
            return Optional.of(new ToolCallRisk(ToolCallRiskType.DELETE, truncate(firstStringParam(input)), REASON_DELETE));
        }
        // 2) 命令风险：任一字符串入参命中破坏性或需确认命令正则
        String riskyCommand = firstMatchingParam(input, destructivePatterns);
        if (riskyCommand != null) {
            return Optional.of(new ToolCallRisk(ToolCallRiskType.RUN_COMMAND, truncate(riskyCommand),
                REASON_DESTRUCTIVE_COMMAND));
        }
        riskyCommand = firstMatchingParam(input, confirmableCommandPatterns);
        if (riskyCommand != null) {
            return Optional.of(new ToolCallRisk(ToolCallRiskType.RUN_COMMAND, truncate(riskyCommand),
                REASON_NON_READONLY_COMMAND));
        }
        // 3) 修改依赖：写类工具的路径入参命中依赖/构建文件
        if (isWriteTool(toolName)) {
            String dependencyTarget = firstMatchingParam(input, dependencyFilePatterns);
            if (dependencyTarget != null) {
                return Optional.of(new ToolCallRisk(ToolCallRiskType.MODIFY_DEPENDENCY, truncate(dependencyTarget),
                    REASON_MODIFY_DEPENDENCY));
            }
        }
        return Optional.empty();
    }

    /** 该工具调用是否被本判定器视为高风险（供拒绝时精确改写风险入参用）。 */
    public boolean isHighRisk(ToolUseBlock use) {
        return assess(use).isPresent();
    }

    /**
     * 该工具是否属于"写类工具"（与 {@link #assess(List)} 统计 {@link ToolCallRiskType#BATCH_MODIFY} 的口径同源）。
     * 供消费方在"批量修改被拒绝"时定位需要一并取消的写调用——批量是<b>整步聚合风险</b>，纯批量普通写入场景下
     * 每个写调用各自 {@link #assess(ToolUseBlock)} 均为空，不能用 {@link #isHighRisk} 判定。
     */
    public boolean isWriteToolName(String toolName) {
        return isWriteTool(toolName);
    }

    /** 工具调用的可读描述（"工具名 + 入参摘要"，超长截断），供逐工具确认卡展示；{@code null} 返回空串。 */
    public String describeTool(ToolUseBlock use) {
        return use == null ? "" : truncate(use.getName() + " " + summarizeInput(use.getInput()));
    }

    /**
     * 只读模式下的 mutating 判定（需拦改的工具）：
     * <ol>
     *   <li>命中强制 mutating 黑名单 → {@code true}（优先级最高）；</li>
     *   <li>命中强制只读白名单 → {@code false}；</li>
     *   <li>启发式：写类工具 / 删除类工具 / 命令执行类关键字工具，或任一字符串入参命中
     *       破坏性 / 需确认命令正则 → {@code true}；</li>
     *   <li>其余（read/search/list/get/query 及 MCP 查询类等只读工具）→ {@code false}，正常放行。</li>
     * </ol>
     */
    public boolean isMutatingTool(ToolUseBlock use) {
        if (use == null) {
            return false;
        }
        String toolName = use.getName();
        if (matchesAny(mutatingToolPatterns, toolName)) {
            return true;
        }
        if (matchesAny(readonlyToolPatterns, toolName)) {
            return false;
        }
        if (isWriteTool(toolName) || isDeleteTool(toolName) || containsAny(toolName, execToolKeywords)) {
            return true;
        }
        Map<String, Object> input = use.getInput();
        return firstMatchingParam(input, destructivePatterns) != null
            || firstMatchingParam(input, confirmableCommandPatterns) != null;
    }

    /**
     * 整体取消改写：把该工具调用的<b>全部字符串入参</b>替换为 {@code notice}。
     * 不同于 {@link #neutralize}（按风险正则精确改写）——纯批量普通写入的路径/内容入参不命中任何
     * 风险正则，必须整体替换才能真正取消写入。无字符串入参返回原块。
     */
    public ToolUseBlock neutralizeAllStringParams(ToolUseBlock use, String notice) {
        if (use == null || CollectionUtils.isEmpty(use.getInput())) {
            return use;
        }
        Map<String, Object> rewritten = new LinkedHashMap<>(use.getInput());
        boolean changed = false;
        for (Map.Entry<String, Object> entry : use.getInput().entrySet()) {
            if (entry.getValue() instanceof CharSequence) {
                rewritten.put(entry.getKey(), notice);
                changed = true;
            }
        }
        return changed ? new ToolUseBlock(use.getId(), use.getName(), rewritten, use.getMetadata()) : use;
    }

    /**
     * 精确改写：把高风险工具调用的风险入参改写为 {@code notice}，让工具无害执行并把结果反馈给 Agent
     * （"改写占位"手法，Agent 据此调整方案）。命中改写返回新块，未命中任何风险入参返回原块。
     */
    public ToolUseBlock neutralize(ToolUseBlock use, String notice) {
        if (use == null || CollectionUtils.isEmpty(use.getInput())) {
            return use;
        }
        Map<String, Object> rewritten = new LinkedHashMap<>(use.getInput());
        boolean changed = false;
        boolean deleteTool = isDeleteTool(use.getName());
        for (Map.Entry<String, Object> entry : use.getInput().entrySet()) {
            Object raw = entry.getValue();
            if (!(raw instanceof CharSequence)) {
                continue;
            }
            String value = raw.toString();
            // 命令类风险入参、或删除类工具的入参：改写为提示
            if (deleteTool || matchesAny(destructivePatterns, value)
                || matchesAny(confirmableCommandPatterns, value) || matchesAny(dependencyFilePatterns, value)) {
                rewritten.put(entry.getKey(), notice);
                changed = true;
            }
        }
        return changed ? new ToolUseBlock(use.getId(), use.getName(), rewritten, use.getMetadata()) : use;
    }

    // ---------------------- private helpers ----------------------

    private static String[] toLowerArray(List<String> raw) {
        if (CollectionUtils.isEmpty(raw)) {
            return new String[0];
        }
        return raw.stream()
            .filter(kw -> kw != null && !kw.isEmpty())
            .map(String::toLowerCase)
            .toArray(String[]::new);
    }

    private static List<Pattern> compile(List<String> raw) {
        List<Pattern> patterns = new ArrayList<>();
        if (CollectionUtils.isEmpty(raw)) {
            return patterns;
        }
        for (String p : raw) {
            if (p != null && !p.isEmpty()) {
                patterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
            }
        }
        return patterns;
    }

    private boolean isDeleteTool(String toolName) {
        return containsAny(toolName, DELETE_TOOL_KEYWORDS);
    }

    private boolean isWriteTool(String toolName) {
        return containsAny(toolName, WRITE_TOOL_KEYWORDS);
    }

    private static boolean containsAny(String toolName, String[] keywords) {
        if (toolName == null) {
            return false;
        }
        String lower = toolName.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        if (text == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** 返回首个命中给定正则的字符串入参值，无命中返回 null。 */
    private static String firstMatchingParam(Map<String, Object> input, List<Pattern> patterns) {
        if (CollectionUtils.isEmpty(input)) {
            return null;
        }
        for (Object raw : input.values()) {
            if (raw instanceof CharSequence && matchesAny(patterns, raw.toString())) {
                return raw.toString();
            }
        }
        return null;
    }

    /** 首个字符串入参（删除类工具用于展示被删目标），无则返回空串。 */
    private static String firstStringParam(Map<String, Object> input) {
        if (CollectionUtils.isEmpty(input)) {
            return "";
        }
        for (Object raw : input.values()) {
            if (raw instanceof CharSequence) {
                return raw.toString();
            }
        }
        return "";
    }

    /** 入参摘要（供确认卡展示），无入参返回空串；整体长度由 {@link #truncate} 兜底截断。 */
    private static String summarizeInput(Map<String, Object> input) {
        if (CollectionUtils.isEmpty(input)) {
            return "";
        }
        return String.valueOf(input);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TARGET_MAX_LEN ? text : text.substring(0, TARGET_MAX_LEN) + "...";
    }
}
