package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.dialog.DialogStage;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import com.richard.fyoung.customerwork.tool.ToolConstants;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话阶段中间件测试。
 *
 * <p><b>守的是什么 bug</b>：这个状态机此前<b>只有读侧</b>——{@code DialogStageService} 的
 * {@code set} / {@code advance} 在全仓生产代码里零调用方，每个会话永远停在 {@code GREETING}，
 * 而 GREETING 当时的文案是"暂不调用业务工具"。于是每一轮对话的系统提示词末尾都写着让模型别调工具，
 * 不报任何错，只表现为工具调用率被无声压低。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class DialogStageMiddlewareTest {

    private static final String SESSION = "sess-1";

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId(SESSION).build();
    }

    private ActingInput acting(String... toolNames) {
        return new ActingInput(List.of(toolNames).stream()
            .map(n -> new ToolUseBlock("id-" + n, n, Map.of()))
            .toList());
    }

    /**
     * GREETING 的文案不能禁止工具调用，否则整个状态机死锁。
     *
     * <p>推进信号是"模型发起了业务工具调用"，而每个会话都从 GREETING 起步。
     * 文案里写"暂不调用业务工具"就意味着模型永远不产生推进信号，
     * 会话锁死在接待阶段，其余四个阶段一次都用不上。</p>
     */
    @Test
    @DisplayName("GREETING 文案不得禁止工具调用，否则状态机死锁")
    void greetingPromptMustNotForbidToolCalls() {
        String fragment = DialogStage.GREETING.promptFragment();

        assertFalse(fragment.contains("暂不调用业务工具"),
            "GREETING 禁止调用工具会让状态机永远推进不了，实际文案：" + fragment);
        assertTrue(fragment.contains("直接调用对应工具"),
            "应当明确允许意图清晰时直接办理，实际文案：" + fragment);
    }

    @Test
    @DisplayName("发起业务工具即从 GREETING 推进到 PROCESSING")
    void businessToolCallAdvancesToProcessing() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);

        assertEquals(DialogStage.GREETING, service.current(SESSION), "初始应为接待阶段");

        mw.onActing(null, ctx(), acting("queryOrder"), in -> Flux.empty()).blockLast();

        assertEquals(DialogStage.PROCESSING, service.current(SESSION),
            "模型能调工具说明意图已明确，应进入业务处理阶段");
    }

    @Test
    @DisplayName("发起转人工工具即切到 ESCALATED")
    void handoffToolSwitchesToEscalated() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);

        mw.onActing(null, ctx(), acting(ToolConstants.TRANSFER_TO_HUMAN), in -> Flux.empty()).blockLast();

        assertEquals(DialogStage.ESCALATED, service.current(SESSION));
    }

    /** 坐席已接手，智能体不该再把自己切回业务处理。 */
    @Test
    @DisplayName("ESCALATED 是终态，后续业务工具不再改变阶段")
    void escalatedIsTerminal() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);
        service.set(SESSION, DialogStage.ESCALATED);

        mw.onActing(null, ctx(), acting("queryOrder"), in -> Flux.empty()).blockLast();

        assertEquals(DialogStage.ESCALATED, service.current(SESSION),
            "转人工后不应被业务工具调用拉回 PROCESSING");
    }

    @Test
    @DisplayName("一轮回复正常结束后从 PROCESSING 收尾到 CONFIRMING")
    void completedTurnAdvancesProcessingToConfirming() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);
        service.set(SESSION, DialogStage.PROCESSING);

        mw.onAgent(null, ctx(), new AgentInput(List.of()), in -> Flux.<AgentEvent>empty()).blockLast();

        assertEquals(DialogStage.CONFIRMING, service.current(SESSION));
    }

    /**
     * 出错的那一轮不能被记成"业务已办妥"。
     *
     * <p>这是选 {@code doOnComplete} 而不是 {@code doFinally} 的理由：后者在错误与取消路径上
     * 同样会跑，会把炸掉的一轮推进到 CONFIRMING，下一轮的提示词就让模型
     * 向用户复述一个根本没发生的处理结果。</p>
     */
    @Test
    @DisplayName("出错的一轮不得推进阶段")
    void failedTurnDoesNotAdvance() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);
        service.set(SESSION, DialogStage.PROCESSING);

        try {
            mw.onAgent(null, ctx(), new AgentInput(List.of()),
                in -> Flux.<AgentEvent>error(new IllegalStateException("模型调用失败"))).blockLast();
        } catch (Exception expected) {
            // 异常本身由上层处理，这里只关心阶段有没有被错误地推进
        }

        assertEquals(DialogStage.PROCESSING, service.current(SESSION),
            "这一轮失败了，不该被记成业务已办妥");
    }

    @Test
    @DisplayName("系统提示词按当前阶段追加对应指令")
    void systemPromptCarriesCurrentStageFragment() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);
        service.set(SESSION, DialogStage.ESCALATED);

        String prompt = mw.onSystemPrompt(null, ctx(), "你是客服助手").block();

        assertTrue(prompt.startsWith("你是客服助手"), "原提示词必须保留");
        assertTrue(prompt.contains(DialogStage.ESCALATED.promptFragment()),
            "应追加当前阶段的聚焦指令");
    }

    /** 没有工具调用时不该凭空推进——阶段流转只认可观测的事实。 */
    @Test
    @DisplayName("没有工具调用则不推进阶段")
    void noToolCallKeepsStage() {
        DialogStageService service = new DialogStageService();
        DialogStageMiddleware mw = new DialogStageMiddleware(service);

        mw.onActing(null, ctx(), new ActingInput(List.of()), in -> Flux.empty()).blockLast();

        assertEquals(DialogStage.GREETING, service.current(SESSION));
    }
}
