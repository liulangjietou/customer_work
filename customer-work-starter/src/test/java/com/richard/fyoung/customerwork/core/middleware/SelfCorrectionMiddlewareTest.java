package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拦住「凭空说钱已经退了」这条防线的完整链路行为。
 *
 * @author owlzhangfq@gmail.com
 */
class SelfCorrectionMiddlewareTest {

    private HandoffService handoffService;
    private AuditSink auditSink;
    private SelfCorrectionMiddleware middleware;

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getSelfCorrection().setEnabled(true);
        middleware = new SelfCorrectionMiddleware(props,
            provider(handoffService), provider(auditSink), provider(null));
    }

    /**
     * 这条是最要紧的一条。
     *
     * <p>只看 {@code AgentResultEvent} 在流式路径上是假性生效——接入层把增量逐片推给前端，
     * 最终结果会被丢弃，等它到达时用户早就读完了。同一个坑出站敏感词过滤已经踩过。</p>
     */
    @Test
    @DisplayName("流式路径下「已退款」不会漏到用户屏幕上")
    void blocksClaimOnStreamingPath() {
        List<AgentEvent> out = runStream(List.of(), List.of("您的订单", "已", "退", "款", "，请查收"));

        String emitted = deltaText(out);
        assertFalse(emitted.contains("已退款"),
            "关键词漏进了流式输出——检测等于白做，用户看到的仍是未经核实的资金结论：" + emitted);
        assertTrue(emitted.startsWith("您的订单"), "命中点之前的正文应当照常送达：" + emitted);
        assertTrue(emitted.contains("未经系统核实"), "必须补一句明确否定前文的澄清：" + emitted);
    }

    /**
     * 最隐蔽的一种：链路上看一切正常，工具调了也成功了，错的是模型对工具语义的理解。
     *
     * <p>{@code submitRefund} 的工具描述写着「只生成待人工确认的退款工单，不会直接打款」。</p>
     */
    @Test
    @DisplayName("只调了 submitRefund 就说「已退款」照样拦——工单不等于已打款")
    void submitRefundIsNotEvidence() {
        List<AgentEvent> out = runStream(List.of("submitRefund"), List.of("好的，", "已退款", "了"));

        assertFalse(deltaText(out).contains("已退款"),
            "调了 submitRefund 只说明生成了工单，不能作为「钱已退」的依据");
        verify(handoffService).create(anyString(), contains("已退款"));
    }

    @Test
    @DisplayName("查过退款进度后说「已退款」是正常转述，原样放行")
    void passesWhenEvidenceToolWasCalled() {
        List<AgentEvent> out = runStream(List.of("queryRefundProgress"),
            List.of("经查询，", "您的订单", "已退款", "，请注意查收"));

        String emitted = deltaText(out);
        assertTrue(emitted.contains("已退款"), "转述系统查到的真实状态不该被拦：" + emitted);
        assertFalse(emitted.contains("未经系统核实"), "正常转述不该被追加澄清");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("命中后自动转人工并留下审计")
    void handsOffAndAuditsOnHit() {
        runStream(List.of(), List.of("已到账"));

        verify(handoffService).create(anyString(), contains("已到账"));
        verify(auditSink).record(contains("unverified-claim"), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("非流式路径（工作台/渠道）在最终结果上改写")
    void rewritesFinalResultOnNonStreamingPath() {
        List<AgentEvent> out = middleware.onAgent(null, ctx(), input(),
            in -> Flux.just(new AgentResultEvent(msg("已为您退款，三日内到账"))))
            .collectList().block();

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        String text = result.getResult().getTextContent();
        assertTrue(text.contains("未经系统核实"), "非流式路径也必须补澄清：" + text);
        verify(handoffService).create(anyString(), anyString());
    }

    @Test
    @DisplayName("普通答复完全透传，一个字不少")
    void passesThroughNormalReply() {
        List<AgentEvent> out = runStream(List.of("queryOrder"),
            List.of("您的订单", "正在配送中，", "预计明天送达"));

        assertEquals("您的订单正在配送中，预计明天送达", deltaText(out));
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("关闭时完全不介入")
    void doesNothingWhenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getSelfCorrection().setEnabled(false);
        SelfCorrectionMiddleware off = new SelfCorrectionMiddleware(props,
            provider(handoffService), provider(auditSink), provider(null));

        List<AgentEvent> out = off.onAgent(null, ctx(), input(),
            in -> Flux.just(new TextBlockDeltaEvent("r", "b", "已退款")))
            .collectList().block();

        assertEquals("已退款", deltaText(out), "关闭时应原样透传");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    /**
     * 缺省即开启。
     *
     * <p>此前默认关闭，配合「只打一行日志」的实现，等于这条防线完全不存在——
     * 而它拦的是直接的客诉与合规风险，不该是一个需要有人想起来去打开的开关。</p>
     */
    @Test
    @DisplayName("默认配置即为开启")
    void enabledByDefault() {
        assertTrue(new CustomerWorkProperties().getHooks().getSelfCorrection().isEnabled());
    }

    /**
     * 已知的误判方向，<b>刻意接受</b>——这条测试记录当前行为，不是在描述一个待修的 bug。
     *
     * <p>用户问「什么情况下算已退款」这类<b>政策解释</b>问题时，模型不会去查订单，
     * 回复里出现关键词就会被拦下并误转一次人工。</p>
     *
     * <p>为什么不修：让「查过知识库」也算依据（把 {@code searchKnowledge} 加进
     * {@code evidenceTools}）确实能消掉这类误判，但知识库内容与某个订单的资金状态<b>毫无关系</b>，
     * 拿它当「钱已退」的依据是错的——那等于为了少转几个人工，把真正要防的那种情形也放行了。
     * 两边的代价不对称：误转一次人工，坐席看一眼几秒钟；漏拦一次资金误告知，是实打实的客诉。</p>
     *
     * <p>真要收敛误判，正确的方向是让关键词更贴近「对本单的断言」（例如只收「已为您退款」
     * 这类第二人称说法），而不是放宽依据判定。运营可以通过 {@code payment-keywords} 自行调整。</p>
     */
    @Test
    @DisplayName("政策解释类问答会被误拦——已知且刻意接受的方向")
    void policyExplanationIsFalsePositiveByDesign() {
        List<AgentEvent> out = runStream(List.of(),
            List.of("按照平台规则，", "款项已退", "回原支付账户即视为完成"));

        assertFalse(deltaText(out).contains("款项已退"),
            "当前行为就是拦下——若这条断言变了，说明有人放宽了判定，"
                + "请先回答：放宽之后，凭空断言「钱已退」的那种情形还拦得住吗？");
    }

    // ---------- 辅助 ----------

    /**
     * 跑一遍链路：工具调用事件先到，随后是正文增量。
     *
     * <p>这正是真实事件流的形态——模型先发起工具调用再生成答复，
     * 两者走同一条流，中间件因此不必在 onActing 与 onAgent 之间共享状态。</p>
     */
    private List<AgentEvent> runStream(List<String> toolNames, List<String> deltas) {
        return middleware.onAgent(null, ctx(), input(), in -> {
            Flux<AgentEvent> calls = Flux.fromIterable(toolNames)
                .map(name -> (AgentEvent) new ToolCallStartEvent("r", "t-" + name, name));
            Flux<AgentEvent> body = Flux.fromIterable(deltas)
                .map(d -> (AgentEvent) new TextBlockDeltaEvent("r", "b", d));
            return calls.concatWith(body).concatWith(Flux.just(new TextBlockEndEvent("r", "b")));
        }).collectList().block();
    }

    private String deltaText(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent delta) {
                sb.append(delta.getDelta());
            }
        }
        return sb.toString();
    }

    private AgentInput input() {
        return new AgentInput(List.of(msg("用户问题")));
    }

    private Msg msg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text(text).build()).build();
    }

    private RuntimeContext ctx() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("u1:conv-1");
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
