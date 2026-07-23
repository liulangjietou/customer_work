package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import com.richard.fyoung.customeradmin.workspace.runtime.SandboxRiskDetector;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 执行模式决策器（纯决策、无副作用，是五档语义的单一权威与单测主战场）：给定
 * {@link ExecutionMode} 与本 acting 步的工具调用清单，产出 {@link ModeDecision}。
 * 内部复用 {@link SandboxRiskDetector} 的既有风险判定，不重复造轮子；拦截/挂起等副作用交给
 * {@code ExecutionModeMiddleware} 执行（决策与执行分离）。
 *
 * <h3>五档语义</h3>
 * <ul>
 *   <li>{@link ExecutionMode#AUTO}：{@code assess} 命中高风险 → CONFIRM，否则 PASS；</li>
 *   <li>{@link ExecutionMode#MANUAL}：只要含工具调用即 CONFIRM，逐工具列 {@code EXECUTE_TOOL}，拒绝整体取消；</li>
 *   <li>{@link ExecutionMode#ACCEPT_EDITS}：同 AUTO，但把 BATCH_MODIFY / MODIFY_DEPENDENCY 视为编辑过滤掉，
 *       过滤后为空则 PASS；DELETE / RUN_COMMAND 仍 CONFIRM；</li>
 *   <li>{@link ExecutionMode#PLAN}：只读研究，不弹卡，mutating 工具拦改（PLAN_BLOCK）；</li>
 *   <li>{@link ExecutionMode#BYPASS}：纯透传（PASS，护栏仍是最后防线）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExecutionModePolicy {

    private final SandboxRiskDetector riskDetector;

    public ExecutionModePolicy(SandboxRiskDetector riskDetector) {
        this.riskDetector = riskDetector;
    }

    /**
     * 决策本步该怎么处理。{@code mode} 为 {@code null}（未指定）时按 {@link ExecutionMode#AUTO} 处理——
     * 调用方（中间件）应在传入前完成"未指定→全局回落"，这里再兜一层保证纯函数完备。
     */
    public ModeDecision decide(ExecutionMode mode, List<ToolUseBlock> toolCalls) {
        if (CollectionUtils.isEmpty(toolCalls)) {
            return ModeDecision.pass();
        }
        ExecutionMode effective = mode != null ? mode : ExecutionMode.AUTO;
        switch (effective) {
            case BYPASS:
                return ModeDecision.pass();
            case PLAN:
                return decidePlan(toolCalls);
            case MANUAL:
                return decideManual(toolCalls);
            case ACCEPT_EDITS:
                return decideAcceptEdits(toolCalls);
            case AUTO:
            default:
                return decideAuto(toolCalls);
        }
    }

    /** AUTO：assess 命中高风险即挂起，风险改写策略 RISK_BASED（含批量修改整步取消）。 */
    private ModeDecision decideAuto(List<ToolUseBlock> toolCalls) {
        List<PlanAction> actions = riskDetector.assess(toolCalls);
        if (actions.isEmpty()) {
            return ModeDecision.pass();
        }
        return ModeDecision.confirm(actions, ModeDecision.Neutralization.RISK_BASED, hasBatchModify(actions));
    }

    /**
     * ACCEPT_EDITS：在 AUTO 基础上把编辑类（BATCH_MODIFY / MODIFY_DEPENDENCY）从确认清单过滤掉自动放行，
     * DELETE / RUN_COMMAND 仍确认；过滤后为空则放行。过滤掉 BATCH_MODIFY 后不再有批量整步取消需求。
     */
    private ModeDecision decideAcceptEdits(List<ToolUseBlock> toolCalls) {
        List<PlanAction> filtered = riskDetector.assess(toolCalls).stream()
            .filter(a -> !SandboxRiskDetector.ACTION_BATCH_MODIFY.equals(a.type())
                && !SandboxRiskDetector.ACTION_MODIFY_DEPENDENCY.equals(a.type()))
            .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return ModeDecision.pass();
        }
        return ModeDecision.confirm(filtered, ModeDecision.Neutralization.RISK_BASED, false);
    }

    /** MANUAL：逐工具列 EXECUTE_TOOL 确认项，拒绝时全部工具整体取消（ALL_TOOLS）。 */
    private ModeDecision decideManual(List<ToolUseBlock> toolCalls) {
        List<PlanAction> actions = toolCalls.stream()
            .map(riskDetector::manualToolAction)
            .collect(Collectors.toList());
        return ModeDecision.confirm(actions, ModeDecision.Neutralization.ALL_TOOLS, false);
    }

    /** PLAN：与工具调用一一对应地判定 mutating（need 拦改），只读工具放行。 */
    private ModeDecision decidePlan(List<ToolUseBlock> toolCalls) {
        List<Boolean> flags = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock use : toolCalls) {
            flags.add(riskDetector.isMutatingTool(use));
        }
        return ModeDecision.planBlock(flags);
    }

    private boolean hasBatchModify(List<PlanAction> actions) {
        return actions.stream().anyMatch(a -> SandboxRiskDetector.ACTION_BATCH_MODIFY.equals(a.type()));
    }
}
