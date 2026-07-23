package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminExecutionModeProperties;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 沙箱高风险操作判定器：把"哪些工具调用算高风险"的规则收拢到唯一一处，供两个消费方复用——
 * <ul>
 *   <li>{@code SandboxGuardMiddleware}（最后防线）：只用 {@link #matchesDestructive(String)} 判定
 *       {@link AdminSandboxProperties.Guard} 的破坏性命令子集，命中即静默改写（行为不变）；</li>
 *   <li>{@code ExecutionModeMiddleware}（五档模式闸门）：经 {@code ExecutionModePolicy} 复用本判定器——
 *       {@link #assess(java.util.List)} 判定"需人工确认"集合（删除 / 非只读命令 / 改依赖 / 批量修改 &gt; N），
 *       {@link #isMutatingTool(ToolUseBlock)} 判定 PLAN 档需拦改的 mutating 工具，
 *       {@link #manualToolAction(ToolUseBlock)} 生成 MANUAL 档逐工具确认项。</li>
 * </ul>
 *
 * <p>破坏性命令正则来自 {@link AdminSandboxProperties.Guard#getDestructivePatterns()}（与护栏同源，不两处维护），
 * HITL 在其之上叠加 {@link AdminSandboxProperties.Hitl} 的确认级规则。判定是纯字符串/工具名匹配，
 * 无副作用（不改写、不落库），改写动作各消费方自持。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SandboxRiskDetector {

    /** 计划动作类型（{@code plan} 事件 actions[].type）。 */
    static final String ACTION_DELETE = "DELETE";
    static final String ACTION_RUN_COMMAND = "RUN_COMMAND";
    /** public：跨包（{@code runtime.mode}）的 {@code ExecutionModePolicy} 过滤编辑类动作时引用。 */
    public static final String ACTION_MODIFY_DEPENDENCY = "MODIFY_DEPENDENCY";
    /** public：{@code ExecutionModePolicy}/{@code ExecutionModeMiddleware} 跨包判定批量修改聚合风险时引用。 */
    public static final String ACTION_BATCH_MODIFY = "BATCH_MODIFY";
    /** MANUAL 档：每个含工具调用的 acting 步都逐工具挂起确认时的动作类型。 */
    public static final String ACTION_EXECUTE_TOOL = "EXECUTE_TOOL";

    /** MANUAL 档单条工具确认项的原因文案。 */
    private static final String MANUAL_CONFIRM_REASON = "Manual 模式：工具执行需人工确认";

    /** 工具名分类关键字（{@code ToolUseBlock} 不带来源标识，只能按名字启发式归类）。 */
    private static final String[] DELETE_TOOL_KEYWORDS = {"delete", "remove", "unlink"};
    private static final String[] WRITE_TOOL_KEYWORDS = {"write", "edit", "save", "create", "replace", "patch", "insert"};

    /** target 展示截断长度，避免超长命令撑爆 plan 卡片。 */
    private static final int TARGET_MAX_LEN = 200;

    private final List<Pattern> destructivePatterns;
    private final List<Pattern> confirmableCommandPatterns;
    private final List<Pattern> dependencyFilePatterns;
    private final int batchModifyThreshold;
    /** PLAN 档：命令执行类工具名关键字（可配置，见 {@link AdminExecutionModeProperties}）。 */
    private final String[] execToolKeywords;
    /** PLAN 档：强制视为只读的工具名正则白名单（命中放行）。 */
    private final List<Pattern> planReadonlyToolPatterns;
    /** PLAN 档：强制视为 mutating 的工具名正则黑名单（命中拦改，优先级最高）。 */
    private final List<Pattern> planMutatingToolPatterns;

    /**
     * 单参构造（无执行模式配置时）：命令执行类关键字与 PLAN 白/黑名单回落默认值。供既有单测与
     * 只依赖沙箱护栏/HITL 判定的调用方使用，行为与引入五档模式前逐字节等价。
     */
    public SandboxRiskDetector(AdminSandboxProperties properties) {
        this(properties, new AdminExecutionModeProperties());
    }

    @Autowired
    public SandboxRiskDetector(AdminSandboxProperties properties, AdminExecutionModeProperties executionModeProperties) {
        this.destructivePatterns = compile(properties.getGuard().getDestructivePatterns());
        this.confirmableCommandPatterns = compile(properties.getHitl().getConfirmableCommandPatterns());
        this.dependencyFilePatterns = compile(properties.getHitl().getDependencyFilePatterns());
        this.batchModifyThreshold = properties.getHitl().getBatchModifyThreshold();
        this.execToolKeywords = toLowerArray(executionModeProperties.getExecToolKeywords());
        this.planReadonlyToolPatterns = compile(executionModeProperties.getPlanReadonlyToolPatterns());
        this.planMutatingToolPatterns = compile(executionModeProperties.getPlanMutatingToolPatterns());
    }

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

    /**
     * 是否命中 {@link AdminSandboxProperties.Guard} 的破坏性命令正则（{@code SandboxGuardMiddleware} 复用此判定）。
     * 与护栏改造前的私有 {@code isDestructive} 逐字节等价。
     */
    public boolean matchesDestructive(String text) {
        return matchesAny(destructivePatterns, text);
    }

    /**
     * 评估一批工具调用（一个 acting 步）的高风险动作清单：每个高风险工具调用产出至多一条 {@link PlanAction}，
     * 另按"单轮写文件工具数超阈值"补一条 {@link #ACTION_BATCH_MODIFY}。返回空表示本步无需人工确认。
     */
    public List<PlanAction> assess(List<ToolUseBlock> toolCalls) {
        List<PlanAction> actions = new ArrayList<>();
        if (CollectionUtils.isEmpty(toolCalls)) {
            return actions;
        }
        int writeToolCount = 0;
        for (ToolUseBlock use : toolCalls) {
            assess(use).ifPresent(actions::add);
            if (isWriteTool(use.getName())) {
                writeToolCount++;
            }
        }
        if (writeToolCount > batchModifyThreshold) {
            actions.add(new PlanAction(ACTION_BATCH_MODIFY, writeToolCount + " files",
                "单轮批量修改 " + writeToolCount + " 个文件（超过阈值 " + batchModifyThreshold + "）"));
        }
        return actions;
    }

    /**
     * 单个工具调用的高风险判定（按严重度短路取第一条）：删除文件 &gt; 破坏性命令 &gt; 非只读命令 &gt; 改依赖。
     */
    public Optional<PlanAction> assess(ToolUseBlock use) {
        if (use == null) {
            return Optional.empty();
        }
        Map<String, Object> input = use.getInput();
        String toolName = use.getName();
        // 1) 删除文件：删除类工具，或 shell 命令命中破坏性/rm 类
        if (isDeleteTool(toolName)) {
            return Optional.of(new PlanAction(ACTION_DELETE, truncate(firstStringParam(input)), "删除文件"));
        }
        // 2) 命令风险：任一字符串入参命中破坏性或需确认命令正则
        String riskyCommand = firstMatchingParam(input, destructivePatterns);
        if (riskyCommand != null) {
            return Optional.of(new PlanAction(ACTION_RUN_COMMAND, truncate(riskyCommand), "执行破坏性命令"));
        }
        riskyCommand = firstMatchingParam(input, confirmableCommandPatterns);
        if (riskyCommand != null) {
            return Optional.of(new PlanAction(ACTION_RUN_COMMAND, truncate(riskyCommand), "执行非只读命令"));
        }
        // 3) 修改依赖：写类工具的路径入参命中依赖/构建文件
        if (isWriteTool(toolName)) {
            String dependencyTarget = firstMatchingParam(input, dependencyFilePatterns);
            if (dependencyTarget != null) {
                return Optional.of(new PlanAction(ACTION_MODIFY_DEPENDENCY, truncate(dependencyTarget), "修改依赖/构建文件"));
            }
        }
        return Optional.empty();
    }

    /** 该工具调用是否被本判定器视为高风险（供拒绝时精确改写风险入参用）。 */
    public boolean isHighRisk(ToolUseBlock use) {
        return assess(use).isPresent();
    }

    /**
     * 该工具是否属于"写类工具"（与 {@link #assess(java.util.List)} 统计 BATCH_MODIFY 的口径同源）。
     * 供中间件在"批量修改被拒绝"时定位需要一并取消的写调用——BATCH_MODIFY 是<b>整步聚合风险</b>，
     * 纯批量普通写入场景下每个写调用各自 {@link #assess(ToolUseBlock)} 均为空，不能用
     * {@link #isHighRisk} 判定，否则拒绝后所有写入原样执行（拒绝失效）。
     */
    public boolean isWriteToolName(String toolName) {
        return isWriteTool(toolName);
    }

    /**
     * MANUAL 档：把一个工具调用整体描述成一条待确认动作（{@link #ACTION_EXECUTE_TOOL}）——
     * target 为"工具名 + 入参摘要"截断，reason 为固定文案。MANUAL 不区分风险高低，逐工具确认。
     */
    public PlanAction manualToolAction(ToolUseBlock use) {
        String target = use == null ? "" : truncate(use.getName() + " " + summarizeInput(use.getInput()));
        return new PlanAction(ACTION_EXECUTE_TOOL, target, MANUAL_CONFIRM_REASON);
    }

    /**
     * PLAN 档 mutating 判定（只读研究模式下需拦改的工具）：
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
        if (matchesAny(planMutatingToolPatterns, toolName)) {
            return true;
        }
        if (matchesAny(planReadonlyToolPatterns, toolName)) {
            return false;
        }
        if (isWriteTool(toolName) || isDeleteTool(toolName) || containsAny(toolName, execToolKeywords)) {
            return true;
        }
        Map<String, Object> input = use.getInput();
        return firstMatchingParam(input, destructivePatterns) != null
            || firstMatchingParam(input, confirmableCommandPatterns) != null;
    }

    /** 入参摘要（供 MANUAL 确认卡展示），无入参返回空串；整体长度由 {@link #truncate} 兜底截断。 */
    private static String summarizeInput(Map<String, Object> input) {
        if (CollectionUtils.isEmpty(input)) {
            return "";
        }
        return String.valueOf(input);
    }

    /**
     * 批量修改被拒绝时的整体取消改写：把该工具调用的<b>全部字符串入参</b>替换为 {@code notice}。
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
     * 拒绝/超时时把某个高风险工具调用的风险入参改写为 {@code notice}，让工具无害执行并把结果反馈给 Agent
     * （沿用 {@code SandboxGuardMiddleware} 的"改写占位"手法，Agent 据此调整方案）。命中改写返回新块，
     * 未命中任何风险入参返回原块。
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
            // 命令类风险入参、或删除类工具的入参：改写为拒绝提示
            if (deleteTool || matchesAny(destructivePatterns, value)
                || matchesAny(confirmableCommandPatterns, value) || matchesAny(dependencyFilePatterns, value)) {
                rewritten.put(entry.getKey(), notice);
                changed = true;
            }
        }
        return changed ? new ToolUseBlock(use.getId(), use.getName(), rewritten, use.getMetadata()) : use;
    }

    // ---------------------- private helpers ----------------------

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

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TARGET_MAX_LEN ? text : text.substring(0, TARGET_MAX_LEN) + "...";
    }
}
