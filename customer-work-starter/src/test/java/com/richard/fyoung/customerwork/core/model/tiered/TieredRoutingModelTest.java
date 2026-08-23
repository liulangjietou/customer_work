package com.richard.fyoung.customerwork.core.model.tiered;

import com.richard.fyoung.customerwork.core.model.routing.ModelRoutingContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分级路由单测。
 *
 * <p>重点在三条：判定保守（拿不准就走标准档）、能力取交集（否则走经济档那次会当场崩）、
 * 首分片之后不回退（否则用户看到两段拼在一起的错乱内容）。</p>
 * @author owlzhangfq@gmail.com
 */
class TieredRoutingModelTest {

    private static final int MAX_MESSAGES = 4;
    private static final int MAX_LENGTH = 60;

    private Model economy;
    private Model standard;
    private TieredRoutingModel routing;

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .content(TextBlock.builder().text(text).build()).build();
    }

    @BeforeEach
    void setUp() {
        economy = mock(Model.class);
        standard = mock(Model.class);
        when(economy.getModelName()).thenReturn("qwen-turbo");
        when(standard.getModelName()).thenReturn("qwen-max");
        when(economy.stream(any(), any(), any())).thenReturn(Flux.empty());
        when(standard.stream(any(), any(), any())).thenReturn(Flux.empty());
        routing = new TieredRoutingModel(economy, standard,
            new ModelTierPolicy(MAX_MESSAGES, MAX_LENGTH));
    }

    private void call(List<Msg> messages) {
        routing.stream(messages, List.of(), GenerateOptions.builder().build())
            .collectList().block();
    }

    @Test
    void shortSingleTurn_shouldUseEconomy() {
        call(List.of(user("运费怎么算")));

        assertEquals(1L, routing.economyCount());
        assertEquals(0L, routing.standardCount());
    }

    @Test
    void forcedFallback_shouldBypassEconomyAndReachStandardChain() {
        routing.stream(List.of(user("运费怎么算")), List.of(), GenerateOptions.builder().build())
            .contextWrite(ModelRoutingContext::preferFallback)
            .collectList().block();

        assertEquals(0L, routing.economyCount());
        assertEquals(1L, routing.standardCount());
    }

    @Test
    void longQuestion_shouldUseStandard() {
        // 长问题信息量大、约束多，便宜模型容易漏掉其中一半要求
        call(List.of(user("我".repeat(MAX_LENGTH + 1))));

        assertEquals(1L, routing.standardCount());
        assertEquals(0L, routing.economyCount());
    }

    @Test
    void multiTurn_shouldUseStandard() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < MAX_MESSAGES; i++) {
            messages.add(user("问题" + i));
            messages.add(assistant("回答" + i));
        }
        call(messages);

        // 轮数一多说明问题在推进、上下文在累积
        assertEquals(1L, routing.standardCount());
    }

    @Test
    void emptyMessages_shouldUseStandard() {
        call(List.of());

        assertEquals(1L, routing.standardCount(), "判不出来就走标准档，这个方向的不对称是故意的");
    }

    @Test
    void noUserMessage_shouldUseStandard() {
        call(List.of(assistant("你好")));

        assertEquals(1L, routing.standardCount());
    }

    @Test
    void economyFailureBeforeFirstChunk_shouldFallBackToStandard() {
        when(economy.stream(any(), any(), any()))
            .thenReturn(Flux.error(new IllegalStateException("economy down")));

        // 还没吐任何分片，回退是安全的
        List<ChatResponse> result = routing
            .stream(List.of(user("运费怎么算")), List.of(), GenerateOptions.builder().build())
            .collectList().block();

        assertTrue(result != null && result.isEmpty(), "应无异常地走完标准档");
    }

    @Test
    void economyFailureAfterFirstChunk_shouldNotFallBack() {
        ChatResponse chunk = mock(ChatResponse.class);
        when(economy.stream(any(), any(), any()))
            .thenReturn(Flux.concat(Flux.just(chunk), Flux.error(new IllegalStateException("mid-stream"))));

        // 已上屏的文字后面再接主模型的完整回答，用户看到的是错乱的两段——宁可报错
        assertThrows(IllegalStateException.class, () -> routing
            .stream(List.of(user("运费怎么算")), List.of(), GenerateOptions.builder().build())
            .collectList().block());
    }

    @Test
    void structuredOutputSupport_shouldBeIntersection() {
        when(standard.supportsNativeStructuredOutput()).thenReturn(true);
        when(economy.supportsNativeStructuredOutput()).thenReturn(false);

        // 按主模型报会让路由到经济档的那次请求当场崩
        assertFalse(routing.supportsNativeStructuredOutput());
    }

    @Test
    void structuredOutputWithTools_shouldBeIntersection() {
        when(standard.supportsNativeStructuredOutputWithTools()).thenReturn(true);
        when(economy.supportsNativeStructuredOutputWithTools()).thenReturn(true);

        assertTrue(routing.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void contextWindow_shouldTakeSmaller() {
        when(standard.getContextWindowSize()).thenReturn(128_000);
        when(economy.getContextWindowSize()).thenReturn(8_000);

        assertEquals(8_000, routing.getContextWindowSize(), "按大的报会让走经济档时超窗");
    }

    @Test
    void modelName_shouldReportStandard() {
        assertEquals("qwen-max", routing.getModelName(), "档位是内部优化，对外仍是主模型");
    }

    @Test
    void missingTier_shouldFailFast() {
        ModelTierPolicy policy = new ModelTierPolicy(MAX_MESSAGES, MAX_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> new TieredRoutingModel(null, standard, policy));
        assertThrows(IllegalArgumentException.class, () -> new TieredRoutingModel(economy, null, policy));
    }
}
