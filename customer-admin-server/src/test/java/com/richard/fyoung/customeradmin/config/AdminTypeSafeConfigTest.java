package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneAnswer;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneClient;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneQuestion;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneResult;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台的 Jev 装配：读的是客服端同一组键、决策点真的是影子、答复闸门真的执行。
 */
class AdminTypeSafeConfigTest {

    private static final String PARAPHRASE = "款项已原路返回您的支付账户，请留意查收";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AdminTypeSafeConfig.class);

    @Test
    @DisplayName("未开启 Jev：没有决策服务，中间件组照样在（原样透传）")
    void offByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(JevDecisionService.class);
            assertThat(ctx).hasSingleBean(AdminJevMiddlewares.class);
        });
    }

    /**
     * 影子模式的价值在于后台展示的判定就是线上会做的判定。后台若读自己的一组键，
     * 运维只改了一边，后台显示的就不再是线上真实的决策。
     */
    @Test
    @DisplayName("与客服端读同一组 customer-work.typesafe.* 键")
    void readsCustomerWorkKeys() {
        runner.withPropertyValues("customer-work.typesafe.enabled=true", "customer-work.typesafe.api-key=k",
                "customer-work.typesafe.escalation.auto-handoff-min-confidence=0.77")
            .run(ctx -> assertEquals(0.77,
                ctx.getBean(JevDecisionService.class).properties().getEscalation().getAutoHandoffMinConfidence()));
    }

    @Test
    @DisplayName("开启了却没配 Key：启动失败，不留到运行期")
    void enabledWithoutKeyFailsFast() {
        runner.withPropertyValues("customer-work.typesafe.enabled=true").run(ctx -> assertThat(ctx).hasFailed());
    }

    /**
     * 用行为断言运行模式，而不是看字段：高置信要求人工时，影子必须发出决策事件，
     * 且不改模型输入——改了的话运营在后台看到的回复就不是线上会给出的回复。
     */
    @Test
    @DisplayName("决策点跑影子模式：展示判定，不改模型输入")
    void decisionPointsRunInShadow() {
        runner.withBean(JevDecisionService.class, AdminTypeSafeConfigTest::furiousJev).run(ctx -> {
            AdminJevMiddlewares jev = ctx.getBean(AdminJevMiddlewares.class);
            RuntimeContext runtime = RuntimeContext.builder().sessionId("s1").build();

            List<AgentEvent> out = jev.escalation().onAgent(null, runtime, userInput("马上给我转人工"),
                in -> Flux.empty()).collectList().block();
            ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
            AtomicReference<ReasoningInput> seen = new AtomicReference<>();
            jev.escalation().onReasoning(null, runtime, input, in -> {
                seen.set(in);
                return Flux.empty();
            }).blockLast();

            Map<String, Object> decision = onlyDecision(out);
            assertEquals(false, decision.get(JevDecisionEvent.KEY_EXECUTED));
            assertSame(input, seen.get(), "影子模式改了模型输入");
        });
    }

    @Test
    @DisplayName("答复安全闸门真执行：拦下同义表述并展示决策")
    void answerGateExecutes() {
        runner.withBean(JevDecisionService.class, AdminTypeSafeConfigTest::furiousJev).run(ctx -> {
            List<AgentEvent> out = ctx.getBean(AdminJevMiddlewares.class).selfCorrection()
                .onAgent(null, RuntimeContext.builder().sessionId("s1").build(), userInput("退款到了吗"),
                    in -> Flux.just(new AgentResultEvent(assistant(PARAPHRASE))))
                .collectList().block();

            AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
            assertTrue(result.getResult().getTextContent().contains("未经系统核实"), "后台的闸门没有真执行");
            assertEquals(true, onlyDecision(out).get(JevDecisionEvent.KEY_EXECUTED));
        });
    }

    @Test
    @DisplayName("答复闸门的开关同样读客服端的 customer-work.hooks.*")
    void answerGateHonoursCustomerWorkHooks() {
        runner.withBean(JevDecisionService.class, AdminTypeSafeConfigTest::furiousJev)
            .withPropertyValues("customer-work.hooks.self-correction.enabled=false")
            .run(ctx -> {
                List<AgentEvent> out = ctx.getBean(AdminJevMiddlewares.class).selfCorrection()
                    .onAgent(null, RuntimeContext.builder().sessionId("s1").build(), userInput("退款到了吗"),
                        in -> Flux.just(new AgentResultEvent(assistant(PARAPHRASE))))
                    .collectList().block();

                assertEquals(PARAPHRASE, ((AgentResultEvent) out.get(out.size() - 1)).getResult().getTextContent());
            });
    }

    /** 按问题类型作答的通用桩：不依赖任何问题编号，情绪取最高档、是/否取高概率。 */
    private static JevDecisionService furiousJev() {
        SystemOneClient client = (state, questions, timeout) -> {
            Map<String, SystemOneAnswer> answers = new HashMap<>();
            questions.forEach((id, question) -> {
                if (question instanceof SystemOneQuestion.Choice) {
                    answers.put(id, new SystemOneAnswer.Choice("complaint", 0.95));
                } else if (question instanceof SystemOneQuestion.Score) {
                    answers.put(id, new SystemOneAnswer.Score(2.95, 0.93, Map.of(0, 0.0, 1, 0.0, 2, 0.05, 3, 0.95)));
                } else {
                    answers.put(id, new SystemOneAnswer.Noul(0.95));
                }
            });
            return Mono.just(new SystemOneResult("jev-test", answers, 5));
        };
        return new JevDecisionService(client, new TypeSafeProperties(), null);
    }

    private static Map<String, Object> onlyDecision(List<AgentEvent> out) {
        List<AgentEvent> decisions = out.stream().filter(JevDecisionEvent::isDecision).toList();
        assertEquals(1, decisions.size(), out.toString());
        return ((CustomEvent) decisions.get(0)).getValue();
    }

    private static AgentInput userInput(String text) {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER)
            .content(TextBlock.builder().text(text).build()).build()));
    }

    private static Msg assistant(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text(text).build()).build();
    }
}
