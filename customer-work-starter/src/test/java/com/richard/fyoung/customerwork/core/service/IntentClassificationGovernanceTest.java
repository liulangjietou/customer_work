package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.AgentGovernanceAssembler;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.core.middleware.LoopGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图分类的输出交给调用方的代码、不交给用户：分类调用上的治理中间件不转人工、不把判定记成对用户的断言。
 *
 * <p>此前分类跑在 {@code intent:<会话>} 上，本轮会话即调用会话，被当成「直接对用户说话」的调用：
 * 用户说「你们说已退款但没到账」，分类器的 {@code summary} 转述了这句话，框架又把结构化参数 JSON 写进了
 * 最终消息的文本，答复安全闸门据此命中，在 {@code intent:<会话>} 上建出一张没人能接到用户的工单，
 * 并记下一次「智能体给出未经核实的资金结论」。循环守卫的轮次用尽同理。</p>
 *
 * <p>用真实的 {@link ReActAgent}、真实的治理装配与脚本模型驱动 {@link CustomerServiceService#classifyIntent}：
 * 结构化输出走哪条路、最终消息里有什么文本，只有真实框架说了算。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class IntentClassificationGovernanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String SESSION = "u1:conv-intent";
    private static final String USER_TEXT = "你们说已退款但我一直没到账";
    /** 分类器对用户原话的转述：含闸门的关键词「已退款」，真实模型完全会这样概括。 */
    private static final String SUMMARY = "用户反映客服称已退款但款项一直未到账";
    private static final String M_CLAIM = "customerwork.selfcorrection.unverified.claim";
    private static final String M_EXHAUSTED = "customerwork.agent.iters.exhausted";
    private static final String AUDIT_CLAIM = "self-correction-unverified-claim";
    private static final int MAX_ITERS = 2;

    private HandoffService handoffService;
    private AuditSink auditSink;
    private MeterRegistry registry;
    private CustomerWorkProperties props;

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        registry = new SimpleMeterRegistry();
        props = new CustomerWorkProperties();
    }

    @Test
    @DisplayName("分类摘要转述了「已退款」：结构化结果原样交给调用方，不转人工、不记成未经核实的资金结论")
    void refundComplaintSummaryIsNotAClaim() {
        IntentResult result = service(classifierModel(validCall())).classifyIntent(SESSION, USER_TEXT)
            .block(TIMEOUT);

        assertEquals(new IntentResult("refund", "", true, SUMMARY), result, "结构化结果应原样交给调用方");
        verify(handoffService, never()).create(anyString(), anyString());
        assertEquals(0.0, registry.counter(M_CLAIM, "stage", "final").count(), "转述用户原话不是对用户的断言");
        verify(auditSink, never()).record(eq(AUDIT_CLAIM), anyMap());
    }

    @Test
    @DisplayName("分类器轮次用尽：退回 other 兜底交给调用方，不转人工，轮次用尽照常记指标")
    void exhaustedClassifierDoesNotHandOff() {
        IntentResult result = service(classifierModel(invalidCall())).classifyIntent(SESSION, USER_TEXT)
            .block(TIMEOUT);

        assertEquals("other", result.intent(), "没拿到结构化结果时走 other 兜底");
        verify(handoffService, never()).create(anyString(), anyString());
        assertEquals(1.0, registry.counter(M_EXHAUSTED).count(), "转不出来的分类照样让运维看得见");
    }

    // ---------- 装配 ----------

    private CustomerServiceService service(Model model) {
        SelfCorrectionMiddleware selfCorrection = new SelfCorrectionMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
        LoopGuardMiddleware loopGuard = new LoopGuardMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
        @SuppressWarnings("unchecked")
        ObjectProvider<MiddlewareBase> middlewares = mock(ObjectProvider.class);
        when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(selfCorrection, loopGuard));
        AgentGovernanceAssembler assembler =
            new AgentGovernanceAssembler(props, new TenantResolver(props), middlewares, null);
        // 不按方法名打桩：凡是返回 ReActAgent 的建 Agent 方法都给一个真实分类器，
        // 与分类器改由哪个工厂方法创建无关；上下文走真实装配器
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class, inv -> {
            if (inv.getMethod().getReturnType() == ReActAgent.class) {
                return classifier(model, assembler);
            }
            if (inv.getMethod().getReturnType() == RuntimeContext.class) {
                return assembler.contextFor(inv.getArgument(0, String.class));
            }
            return Mockito.RETURNS_DEFAULTS.answer(inv);
        });
        return new CustomerServiceService(factory, mock(SessionStateManager.class), props);
    }

    private static ReActAgent classifier(Model model, AgentGovernanceAssembler assembler) {
        ReActAgent.Builder builder = ReActAgent.builder()
            .name("IntentClassifier")
            .sysPrompt("你是意图分类器。")
            .model(model)
            .toolkit(new Toolkit())
            .maxIters(MAX_ITERS);
        assembler.applyTo(builder);
        return builder.build();
    }

    /** 带工具清单的调用给出预设的结构化调用；框架轮次用尽后的收尾调用不带工具，给一段文本。 */
    private static Model classifierModel(ToolUseBlock call) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (tools == null || tools.isEmpty()) {
                    return Flux.just(response(TextBlock.builder().text("抱歉，未能完成分类。").build(), "stop"));
                }
                return Flux.just(response(call, "tool_calls"));
            }

            @Override
            public String getModelName() {
                return "scripted-intent-model";
            }
        };
    }

    /** 合法的结构化调用：参数既给解析后的 input 也给原始 JSON，框架按原始 JSON 校验。 */
    private static ToolUseBlock validCall() {
        Map<String, Object> response = Map.of("intent", "refund", "orderId", "", "urgent", true, "summary", SUMMARY);
        String raw = "{\"response\":{\"intent\":\"refund\",\"orderId\":\"\",\"urgent\":true,\"summary\":\""
            + SUMMARY + "\"}}";
        return new ToolUseBlock("call-" + UUID.randomUUID(), "generate_response", Map.of("response", response), raw,
            null);
    }

    /** 缺少必填的 response：参数校验不过，模型每轮都这样调，直到轮次用尽。 */
    private static ToolUseBlock invalidCall() {
        return new ToolUseBlock("call-" + UUID.randomUUID(), "generate_response", Map.of("intent", "refund"),
            "{\"intent\":\"refund\"}", null);
    }

    private static ChatResponse response(ContentBlock content, String finishReason) {
        return ChatResponse.builder().id(UUID.randomUUID().toString()).content(List.of(content))
            .usage(new ChatUsage(1, 1, 0.0)).finishReason(finishReason).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
