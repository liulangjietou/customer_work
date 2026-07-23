package com.richard.fyoung.customeradmin.workspace.runtime.mode;

import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;

import java.util.Collections;
import java.util.List;

/**
 * {@code ExecutionModePolicy} 的决策结果（纯数据，无副作用）：把"某模式下这批工具调用该怎么处理"
 * 归一成三种终态，供 {@code ExecutionModeMiddleware} 执行——决策与拦截执行分离，框架耦合只落在中间件一层。
 *
 * <ul>
 *   <li>{@link Kind#PASS}：直接透传放行；</li>
 *   <li>{@link Kind#CONFIRM}：挂起等人工确认，携带待确认动作清单与拒绝改写策略；</li>
 *   <li>{@link Kind#PLAN_BLOCK}：PLAN 档拦改 mutating 工具，携带与工具调用一一对应的 mutating 标记。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public final class ModeDecision {

    /** 决策终态。 */
    public enum Kind {
        PASS,
        CONFIRM,
        PLAN_BLOCK
    }

    /** CONFIRM 被拒绝/超时时的入参改写策略。 */
    public enum Neutralization {
        /** 按风险精确改写：单工具高风险改写风险入参、批量修改整步改写写类工具入参（AUTO/ACCEPT_EDITS）。 */
        RISK_BASED,
        /** 整体改写：每个工具调用的全部字符串入参统一改写（MANUAL——整步都需确认）。 */
        ALL_TOOLS
    }

    private final Kind kind;
    /** CONFIRM：待确认的动作清单（用于 PlanEvent 展示）。 */
    private final List<PlanAction> actions;
    /** CONFIRM：拒绝改写策略。 */
    private final Neutralization neutralization;
    /** CONFIRM：本步是否含批量修改聚合风险（RISK_BASED 拒绝时据此整体取消写类工具）。 */
    private final boolean batchModify;
    /** PLAN_BLOCK：与工具调用列表按下标一一对应的 mutating 标记（true=需拦改）。 */
    private final List<Boolean> mutatingFlags;

    private ModeDecision(Kind kind, List<PlanAction> actions, Neutralization neutralization,
                         boolean batchModify, List<Boolean> mutatingFlags) {
        this.kind = kind;
        this.actions = actions;
        this.neutralization = neutralization;
        this.batchModify = batchModify;
        this.mutatingFlags = mutatingFlags;
    }

    public static ModeDecision pass() {
        return new ModeDecision(Kind.PASS, List.of(), null, false, List.of());
    }

    public static ModeDecision confirm(List<PlanAction> actions, Neutralization neutralization, boolean batchModify) {
        return new ModeDecision(Kind.CONFIRM, List.copyOf(actions), neutralization, batchModify, List.of());
    }

    public static ModeDecision planBlock(List<Boolean> mutatingFlags) {
        return new ModeDecision(Kind.PLAN_BLOCK, List.of(), null, false, List.copyOf(mutatingFlags));
    }

    public Kind kind() {
        return kind;
    }

    public List<PlanAction> actions() {
        return Collections.unmodifiableList(actions);
    }

    public Neutralization neutralization() {
        return neutralization;
    }

    public boolean batchModify() {
        return batchModify;
    }

    public List<Boolean> mutatingFlags() {
        return Collections.unmodifiableList(mutatingFlags);
    }
}
