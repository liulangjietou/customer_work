package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务清单采集。
 *
 * @author owlzhangfq@gmail.com
 */
class TaskPlanCaptureMiddlewareTest {

    private static final String CALL_ID = "call-1";

    private MeterRegistry registry;
    private TaskPlanCaptureMiddleware middleware;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        middleware = new TaskPlanCaptureMiddleware(new CustomerWorkProperties(), provider(registry));
    }

    @Test
    @DisplayName("从工具调用参数里采集到任务清单，并带进终止信封")
    void capturesTaskPlan() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String args = "{\"todos\":["
            + "{\"content\":\"提交退货申请\",\"status\":\"completed\",\"priority\":\"high\"},"
            + "{\"content\":\"修改收货地址\",\"status\":\"in_progress\",\"priority\":\"medium\"}]}";

        run(capture, args);

        List<TaskPlanItem> plan = capture.taskPlan();
        assertEquals(2, plan.size());
        assertEquals("提交退货申请", plan.get(0).content());
        assertEquals("in_progress", plan.get(1).status());
        assertEquals(plan, capture.envelope("MSG-1", "回复", "trace-1").taskPlan(),
            "采到的清单必须真的进终止信封，否则前端永远拿不到");
    }

    /**
     * 模型每完成一项就重写整份清单（框架 todo_write 是全量覆盖语义）。
     *
     * <p>累加会得到同一件事的多个历史状态——用户要看的是"现在办到哪了"，不是变更流水。</p>
     */
    @Test
    @DisplayName("多次写入只保留最后一份，不累加历史状态")
    void keepsOnlyLatestPlan() {
        ChatTerminalCapture capture = new ChatTerminalCapture();

        run(capture, "{\"todos\":[{\"content\":\"查订单\",\"status\":\"in_progress\"}]}");
        run(capture, "{\"todos\":[{\"content\":\"查订单\",\"status\":\"completed\"}]}");

        assertEquals(1, capture.taskPlan().size(), "同一件事出现了多份，用户会看到重复条目");
        assertEquals("completed", capture.taskPlan().get(0).status());
    }

    /**
     * 挂上工具不等于模型会用。
     *
     * <p>这个计数长期为零就说明提示词的引导没生效、或这个场景压根不需要拆解——
     * 那时该调整的是提示词或这个能力本身，而不是继续假设它在工作。</p>
     */
    @Test
    @DisplayName("记录模型实际调用了几次，用来回答「到底用不用」")
    void countsActualUsage() {
        ChatTerminalCapture capture = new ChatTerminalCapture();

        run(capture, "{\"todos\":[{\"content\":\"查订单\",\"status\":\"pending\"}]}");

        assertEquals(1.0, registry.counter("customerwork.taskplan.write").count());
    }

    @Test
    @DisplayName("清单条数超过上限时截断，不把终止帧撑大")
    void truncatesOversizedPlan() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getPlan().setMaxSubtasks(3);
        middleware = new TaskPlanCaptureMiddleware(properties, provider(registry));
        ChatTerminalCapture capture = new ChatTerminalCapture();

        StringBuilder args = new StringBuilder("{\"todos\":[");
        for (int i = 0; i < 10; i++) {
            args.append(i > 0 ? "," : "").append("{\"content\":\"事项").append(i).append("\"}");
        }
        run(capture, args.append("]}").toString());

        assertEquals(3, capture.taskPlan().size());
    }

    @Test
    @DisplayName("参数被切成多个分片时仍能完整解析")
    void handlesSplitArguments() {
        ChatTerminalCapture capture = new ChatTerminalCapture();
        String args = "{\"todos\":[{\"content\":\"查订单\",\"status\":\"pending\"}]}";
        int cut = args.length() / 2;

        middleware.onAgent(null, null, input(), in -> Flux.just(
                (AgentEvent) new ToolCallDeltaEvent("r", CALL_ID,
                    TaskPlanCaptureMiddleware.TOOL_TODO_WRITE, args.substring(0, cut)),
                new ToolCallDeltaEvent("r", CALL_ID,
                    TaskPlanCaptureMiddleware.TOOL_TODO_WRITE, args.substring(cut)),
                new ToolCallEndEvent("r", CALL_ID, TaskPlanCaptureMiddleware.TOOL_TODO_WRITE)))
            .contextWrite(ctx -> ChatTerminalCaptureContext.withCapture(ctx, capture))
            .blockLast();

        assertEquals(1, capture.taskPlan().size(), "分片拼接失败——参数是增量到达的，逐片解析必然失败");
    }

    @Test
    @DisplayName("别的工具调用不产生任务清单")
    void ignoresOtherTools() {
        ChatTerminalCapture capture = new ChatTerminalCapture();

        middleware.onAgent(null, null, input(), in -> Flux.just(
                (AgentEvent) new ToolCallDeltaEvent("r", CALL_ID, "queryOrder", "{\"orderId\":\"SO-1\"}"),
                new ToolCallEndEvent("r", CALL_ID, "queryOrder")))
            .contextWrite(ctx -> ChatTerminalCaptureContext.withCapture(ctx, capture))
            .blockLast();

        assertTrue(capture.taskPlan().isEmpty());
    }

    /** 参数坏掉时不能打断对话：进度展示是旁路，用户宁可看不到进度也不愿收到错误。 */
    @Test
    @DisplayName("参数不是合法 JSON 时静默跳过，不打断对话")
    void malformedArgumentsDoNotBreakTurn() {
        ChatTerminalCapture capture = new ChatTerminalCapture();

        run(capture, "{不是合法 JSON");

        assertTrue(capture.taskPlan().isEmpty());
    }

    @Test
    @DisplayName("没有采集上下文时完全透传")
    void passesThroughWithoutCapture() {
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        List<AgentEvent> events = middleware.onAgent(null, null, input(), in -> {
            nextCalled.set(true);
            return Flux.just(new ToolCallEndEvent("r", CALL_ID,
                TaskPlanCaptureMiddleware.TOOL_TODO_WRITE));
        }).collectList().block();

        assertTrue(nextCalled.get());
        assertEquals(1, events.size(), "事件流不得被改写或吞掉");
    }

    private void run(ChatTerminalCapture capture, String arguments) {
        middleware.onAgent(null, null, input(), in -> Flux.just(
                (AgentEvent) new ToolCallDeltaEvent("r", CALL_ID,
                    TaskPlanCaptureMiddleware.TOOL_TODO_WRITE, arguments),
                new ToolCallEndEvent("r", CALL_ID, TaskPlanCaptureMiddleware.TOOL_TODO_WRITE)))
            .contextWrite(ctx -> ChatTerminalCaptureContext.withCapture(ctx, capture))
            .blockLast();
    }

    private AgentInput input() {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER)
            .content(TextBlock.builder().text("我要退货，还要改地址").build()).build()));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
