package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminExecutionModeProperties;
import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import com.richard.fyoung.customerwork.security.ToolCallRisk;
import com.richard.fyoung.customerwork.security.ToolCallRiskDetector;
import com.richard.fyoung.customerwork.security.ToolCallRiskRules;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 沙箱高风险操作判定器的 admin 侧薄壳：把"哪些工具调用算高风险"的判定收拢到唯一一处，供两个消费方复用——
 * <ul>
 *   <li>{@code SandboxGuardMiddleware}（最后防线）：只用 {@link #matchesDestructive(String)} 判定
 *       {@link AdminSandboxProperties.Guard} 的破坏性命令子集，命中即静默改写；</li>
 *   <li>{@code ExecutionModeMiddleware}（五档模式闸门）：经 {@code ExecutionModePolicy} 复用本判定器——
 *       {@link #assess(java.util.List)} 判定"需人工确认"集合（删除 / 非只读命令 / 改依赖 / 批量修改 &gt; N），
 *       {@link #isMutatingTool(ToolUseBlock)} 判定 PLAN 档需拦改的 mutating 工具，
 *       {@link #manualToolAction(ToolUseBlock)} 生成 MANUAL 档逐工具确认项。</li>
 * </ul>
 *
 * <p><b>规则算法不在本类</b>：判定与改写实现是 starter 的 {@link ToolCallRiskDetector}。本类只做三件事：
 * 把 {@link AdminSandboxProperties} / {@link AdminExecutionModeProperties} 两处配置绑成
 * {@link ToolCallRiskRules}；把 {@link ToolCallRisk} 映射成本模块 {@code plan} 事件的 {@link PlanAction}；
 * 承载执行模式特有的 MANUAL 档确认项文案（{@link #ACTION_EXECUTE_TOOL}，非通用风险语义，不下沉）。</p>
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

    private final ToolCallRiskDetector riskDetector;

    /**
     * 单参构造（无执行模式配置时）：命令执行类关键字与 PLAN 白/黑名单回落默认值。供既有单测与
     * 只依赖沙箱护栏/HITL 判定的调用方使用，行为与引入五档模式前逐字节等价。
     */
    public SandboxRiskDetector(AdminSandboxProperties properties) {
        this(properties, new AdminExecutionModeProperties());
    }

    @Autowired
    public SandboxRiskDetector(AdminSandboxProperties properties, AdminExecutionModeProperties executionModeProperties) {
        this.riskDetector = new ToolCallRiskDetector(new ToolCallRiskRules(
            properties.getGuard().getDestructivePatterns(),
            properties.getHitl().getConfirmableCommandPatterns(),
            properties.getHitl().getDependencyFilePatterns(),
            properties.getHitl().getBatchModifyThreshold(),
            executionModeProperties.getExecToolKeywords(),
            executionModeProperties.getPlanReadonlyToolPatterns(),
            executionModeProperties.getPlanMutatingToolPatterns()));
    }

    /**
     * 是否命中 {@link AdminSandboxProperties.Guard} 的破坏性命令正则（{@code SandboxGuardMiddleware} 复用此判定）。
     */
    public boolean matchesDestructive(String text) {
        return riskDetector.matchesDestructive(text);
    }

    /**
     * 评估一批工具调用（一个 acting 步）的高风险动作清单：每个高风险工具调用产出至多一条 {@link PlanAction}，
     * 另按"单轮写文件工具数超阈值"补一条 {@link #ACTION_BATCH_MODIFY}。返回空表示本步无需人工确认。
     */
    public List<PlanAction> assess(List<ToolUseBlock> toolCalls) {
        return riskDetector.assess(toolCalls).stream()
            .map(SandboxRiskDetector::toPlanAction)
            .collect(Collectors.toList());
    }

    /**
     * 单个工具调用的高风险判定（按严重度短路取第一条）：删除文件 &gt; 破坏性命令 &gt; 非只读命令 &gt; 改依赖。
     */
    public Optional<PlanAction> assess(ToolUseBlock use) {
        return riskDetector.assess(use).map(SandboxRiskDetector::toPlanAction);
    }

    /** 该工具调用是否被本判定器视为高风险（供拒绝时精确改写风险入参用）。 */
    public boolean isHighRisk(ToolUseBlock use) {
        return riskDetector.isHighRisk(use);
    }

    /**
     * 该工具是否属于"写类工具"（与 {@link #assess(java.util.List)} 统计 BATCH_MODIFY 的口径同源）。
     * 供中间件在"批量修改被拒绝"时定位需要一并取消的写调用——BATCH_MODIFY 是<b>整步聚合风险</b>，
     * 纯批量普通写入场景下每个写调用各自 {@link #assess(ToolUseBlock)} 均为空，不能用
     * {@link #isHighRisk} 判定，否则拒绝后所有写入原样执行（拒绝失效）。
     */
    public boolean isWriteToolName(String toolName) {
        return riskDetector.isWriteToolName(toolName);
    }

    /**
     * MANUAL 档：把一个工具调用整体描述成一条待确认动作（{@link #ACTION_EXECUTE_TOOL}）——
     * target 为"工具名 + 入参摘要"截断，reason 为固定文案。MANUAL 不区分风险高低，逐工具确认。
     */
    public PlanAction manualToolAction(ToolUseBlock use) {
        return new PlanAction(ACTION_EXECUTE_TOOL, riskDetector.describeTool(use), MANUAL_CONFIRM_REASON);
    }

    /**
     * PLAN 档 mutating 判定（只读研究模式下需拦改的工具）：强制黑名单 &gt; 强制白名单 &gt;
     * 启发式（写/删/命令执行类工具名，或入参命中破坏性/需确认命令正则），其余只读工具放行。
     */
    public boolean isMutatingTool(ToolUseBlock use) {
        return riskDetector.isMutatingTool(use);
    }

    /**
     * 批量修改被拒绝时的整体取消改写：把该工具调用的<b>全部字符串入参</b>替换为 {@code notice}。
     * 不同于 {@link #neutralize}（按风险正则精确改写）——纯批量普通写入的路径/内容入参不命中任何
     * 风险正则，必须整体替换才能真正取消写入。无字符串入参返回原块。
     */
    public ToolUseBlock neutralizeAllStringParams(ToolUseBlock use, String notice) {
        return riskDetector.neutralizeAllStringParams(use, notice);
    }

    /**
     * 拒绝/超时时把某个高风险工具调用的风险入参改写为 {@code notice}，让工具无害执行并把结果反馈给 Agent
     * （沿用 {@code SandboxGuardMiddleware} 的"改写占位"手法，Agent 据此调整方案）。命中改写返回新块，
     * 未命中任何风险入参返回原块。
     */
    public ToolUseBlock neutralize(ToolUseBlock use, String notice) {
        return riskDetector.neutralize(use, notice);
    }

    /** starter 风险类型 → 本模块 {@code plan} 事件动作类型（对外协议是字符串，不直接暴露 starter 枚举名）。 */
    private static PlanAction toPlanAction(ToolCallRisk risk) {
        String type = switch (risk.type()) {
            case DELETE -> ACTION_DELETE;
            case RUN_COMMAND -> ACTION_RUN_COMMAND;
            case MODIFY_DEPENDENCY -> ACTION_MODIFY_DEPENDENCY;
            case BATCH_MODIFY -> ACTION_BATCH_MODIFY;
        };
        return new PlanAction(type, risk.target(), risk.reason());
    }
}
