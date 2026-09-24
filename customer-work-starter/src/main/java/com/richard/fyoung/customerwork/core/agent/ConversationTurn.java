package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.agent.RuntimeContext;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 一轮用户对话：谁在对用户说话、转人工落到哪个会话、内部调用出了状况由谁收尾。
 *
 * <h3>为什么需要它</h3>
 * <p>为这一轮干活的 Agent 调用不止一个：多专家协作的分诊器 / 专家 / 归纳器、Harness 的子智能体。
 * 它们各自跑在派生出来的会话上（{@code <会话>#mas-consult}、框架的 {@code sub-<uuid>}），
 * 以隔离框架按会话缓存的状态——但对用户说话、把人接进来，必须落在<b>用户自己的会话</b>上，
 * 并且只由组织这一轮答复的那一方做一次。此前循环守卫与答复安全闸门拿 {@code ctx.getSessionId()} 转人工，
 * 在这些内部调用里建出来的是一张没人能接到用户的工单；去向说明又追加在专家的中间结论上，
 * 归纳器改写时可以把它丢掉。</p>
 *
 * <h3>判定规则</h3>
 * <p>{@link AgentGovernanceAssembler#contextFor} 为每一次对用户的调用挂上本轮（用户会话即调用会话）。
 * 框架派生子调用的上下文时会原样复制这些属性、只换会话号，多专家编排则显式挂上用户会话——
 * 于是<b>调用会话与本轮会话不一致，就是替本轮干活的内部调用</b>，不需要任何一方记得打标记。</p>
 *
 * <p>内部调用只观测、只上报（{@link #escalate}），由本轮的组织者结算：多专家编排在归纳出最终答复之后
 * 统一追加说明、转一次人工。Harness 父智能体刻意不结算子智能体的上报——子智能体的收尾已经作为工具结果
 * 交给父模型，接下来转不转人工由父模型判断，父智能体自己转不出来时有它自己的循环守卫。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ConversationTurn {

    /** 多条转人工原因合并时的分隔符。 */
    private static final String REASON_SEPARATOR = "；";

    private final String sessionId;
    /** 内部调用上报的待办；多专家并行时会被多个线程同时写入。 */
    private final ConcurrentLinkedQueue<Escalation> escalations = new ConcurrentLinkedQueue<>();

    private ConversationTurn(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 以用户会话开启一轮。
     *
     * @param sessionId 用户会话；与 {@link AgentGovernanceAssembler#contextFor} 同口径，空值归为 {@code default}
     */
    public static ConversationTurn open(String sessionId) {
        return new ConversationTurn(StringUtils.hasText(sessionId) ? sessionId : AgentGovernanceAssembler.DEFAULT_SESSION);
    }

    /**
     * 这次调用所代办的那一轮；调用本身就在对用户说话（或上下文里没有轮次信息）时返回 null。
     */
    public static ConversationTurn delegatedBy(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        ConversationTurn turn = ctx.get(ConversationTurn.class);
        return turn == null || turn.sessionId.equals(ctx.getSessionId()) ? null : turn;
    }

    /** 用户会话：工单与转人工的落点。 */
    public String sessionId() {
        return sessionId;
    }

    /** 内部调用上报一件需要本轮组织者处理的事。 */
    public void escalate(Escalation escalation) {
        if (escalation != null && (escalation.notice() != null || escalation.handoffReason() != null)) {
            escalations.add(escalation);
        }
    }

    public boolean hasEscalations() {
        return !escalations.isEmpty();
    }

    /** 把上报的说明接在最终答复末尾：同样的说明只出现一次（几位专家同时转不出来，用户只需要知道一次）。 */
    public String appendNotices(String reply) {
        StringBuilder settled = new StringBuilder(reply == null ? "" : reply);
        distinct(escalations.stream().map(Escalation::notice).toList()).forEach(settled::append);
        return settled.toString();
    }

    /** 本轮需要转人工时的原因，去重后合并；没有任何一方要求转人工时为空。 */
    public Optional<String> handoffReason() {
        Set<String> reasons = distinct(escalations.stream().map(Escalation::handoffReason).toList());
        return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join(REASON_SEPARATOR, reasons));
    }

    private static Set<String> distinct(List<String> values) {
        Set<String> kept = new LinkedHashSet<>();
        values.stream().filter(StringUtils::hasText).forEach(kept::add);
        return kept;
    }

    /**
     * 内部调用上报的一件事。
     *
     * @param agent         上报方，供日志与审计定位
     * @param notice        要告诉用户的话，接在最终答复末尾；null 表示不需要
     * @param handoffReason 需要转人工时的原因；null 表示不需要
     */
    public record Escalation(String agent, String notice, String handoffReason) {
    }
}
