package com.richard.fyoung.customerwork.core.model.routing;

import com.richard.fyoung.customerwork.core.model.failover.FailoverModel.FallbackModelUnavailableException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyRoutingModelTest {

    @Test
    void explicitHint_shouldSelectFirstMatchingPriorityRule() {
        StubModel standard = new StubModel("standard", false, false);
        StubModel economy = new StubModel("economy", false, false);
        PolicyRoutingModel routing = model(List.of(
            rule(10L, PolicyRouteSpec.Purpose.ECONOMY, 2L, 10,
                new PolicyRouteSpec.Condition(List.of(7L), List.of("web"), null, 100,
                    false, false, "LOW")),
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty()),
            rule(12L, PolicyRouteSpec.Purpose.FALLBACK, 3L, 1000, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, standard, 2L, economy, 3L, new StubModel("fallback", false, false)));

        routing.stream(List.of(user("short")), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> ModelRoutingContext.withHint(context,
                new ModelRouteHint(7L, "WEB", 10, false, false, "LOW")))
            .collectList().block();

        assertEquals(1, economy.calls.get());
        assertEquals(0, standard.calls.get());
    }

    @Test
    void missingExplicitHint_shouldDeriveTokensToolsAndComplexity() {
        StubModel standard = new StubModel("standard", false, false);
        StubModel complex = new StubModel("complex", false, false);
        PolicyRoutingModel routing = model(List.of(
            rule(10L, PolicyRouteSpec.Purpose.COMPLEX_REASONING, 2L, 10,
                new PolicyRouteSpec.Condition(List.of(7L), List.of("web"), 500, null,
                    false, false, "MEDIUM")),
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, standard, 2L, complex));

        routing.stream(List.of(user("x".repeat(2200))), List.of(), GenerateOptions.builder().build())
            .collectList().block();

        assertEquals(1, complex.calls.get());
        assertEquals(0, standard.calls.get());
    }

    @Test
    void forcedFallback_shouldNeverCallNormalCandidate() {
        StubModel standard = new StubModel("standard", false, false);
        StubModel fallback = new StubModel("fallback", false, false);
        PolicyRoutingModel routing = model(List.of(
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty()),
            rule(12L, PolicyRouteSpec.Purpose.FALLBACK, 3L, 1000, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, standard, 3L, fallback));

        List<ChatResponse> result = routing.stream(List.of(user("hello")), List.of(),
                GenerateOptions.builder().build())
            .contextWrite(ModelRoutingContext::preferFallback)
            .collectList().block();

        assertEquals("fallback", result.get(0).getId());
        assertEquals(0, standard.calls.get());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void forcedFallback_shouldFailClosedWhenPolicyHasNoFallback() {
        PolicyRoutingModel routing = model(List.of(
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, new StubModel("standard", false, false)));

        assertThrows(FallbackModelUnavailableException.class, () -> routing
            .stream(List.of(user("hello")), List.of(), GenerateOptions.builder().build())
            .contextWrite(ModelRoutingContext::preferFallback)
            .collectList().block());
    }

    @Test
    void failureBeforeFirstChunk_shouldUsePolicyFallback() {
        StubModel primary = new StubModel("primary", true, false);
        StubModel fallback = new StubModel("fallback", false, false);
        PolicyRoutingModel routing = model(List.of(
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty()),
            rule(12L, PolicyRouteSpec.Purpose.FALLBACK, 2L, 1000, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, primary, 2L, fallback));

        List<ChatResponse> result = routing.stream(List.of(user("hello")), List.of(),
            GenerateOptions.builder().build()).collectList().block();

        assertEquals("fallback", result.get(0).getId());
        assertEquals(1, fallback.calls.get());
    }

    @Test
    void failureAfterFirstChunk_shouldNotSpliceFallbackAnswer() {
        StubModel primary = new StubModel("primary", true, true);
        StubModel fallback = new StubModel("fallback", false, false);
        PolicyRoutingModel routing = model(List.of(
            rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100, PolicyRouteSpec.Condition.empty()),
            rule(12L, PolicyRouteSpec.Purpose.FALLBACK, 2L, 1000, PolicyRouteSpec.Condition.empty())),
            Map.of(1L, primary, 2L, fallback));

        StepVerifier.create(routing.stream(List.of(user("hello")), List.of(),
                GenerateOptions.builder().build()))
            .expectNextMatches(response -> "primary".equals(response.getId()))
            .expectErrorMatches(error -> error.getMessage().contains("primary"))
            .verify();
        assertEquals(0, fallback.calls.get());
    }

    @Test
    void unhealthyMatchedDeployment_shouldUseHealthyDefaultRule() {
        StubModel economy = new StubModel("economy", false, false);
        StubModel standard = new StubModel("standard", false, false);
        PolicyRoutingModel routing = model(List.of(
                rule(10L, PolicyRouteSpec.Purpose.ECONOMY, 2L, 10,
                    new PolicyRouteSpec.Condition(List.of(), List.of(), null, 100,
                        false, false, "LOW")),
                rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100,
                    PolicyRouteSpec.Condition.empty())),
            Map.of(1L, standard, 2L, economy),
            Map.of(2L, new PolicyRouteSpec.Health("UNHEALTHY", false, "AUTO", 8)));

        List<ChatResponse> result = routing.stream(List.of(user("hello")), List.of(),
            GenerateOptions.builder().build()).collectList().block();

        assertEquals("standard", result.get(0).getId());
        assertEquals(0, economy.calls.get());
    }

    @Test
    void unhealthyFallback_shouldNeverBeUsedAfterPrimaryFailure() {
        StubModel primary = new StubModel("primary", true, false);
        StubModel fallback = new StubModel("fallback", false, false);
        PolicyRoutingModel routing = model(List.of(
                rule(11L, PolicyRouteSpec.Purpose.DEFAULT, 1L, 100,
                    PolicyRouteSpec.Condition.empty()),
                rule(12L, PolicyRouteSpec.Purpose.FALLBACK, 2L, 1000,
                    PolicyRouteSpec.Condition.empty())),
            Map.of(1L, primary, 2L, fallback),
            Map.of(2L, new PolicyRouteSpec.Health("UNHEALTHY", false, "AUTO", 4)));

        assertThrows(IllegalStateException.class, () -> routing.stream(List.of(user("hello")),
            List.of(), GenerateOptions.builder().build()).collectList().block());
        assertEquals(0, fallback.calls.get());
    }

    private PolicyRoutingModel model(List<PolicyRouteSpec.Rule> rules, Map<Long, Model> models) {
        return new PolicyRoutingModel(new PolicyRouteSpec(5L, 6L, 3, "hash", 7L, "web", rules), models);
    }

    private PolicyRoutingModel model(List<PolicyRouteSpec.Rule> rules, Map<Long, Model> models,
                                     Map<Long, PolicyRouteSpec.Health> health) {
        return new PolicyRoutingModel(
            new PolicyRouteSpec(5L, 6L, 3, "hash", 7L, "web", rules, health), models);
    }

    private PolicyRouteSpec.Rule rule(Long id, PolicyRouteSpec.Purpose purpose, Long deploymentId,
                                      int priority, PolicyRouteSpec.Condition condition) {
        return new PolicyRouteSpec.Rule(id, purpose, deploymentId, priority, condition);
    }

    private Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private static final class StubModel implements Model {
        private final String name;
        private final boolean fail;
        private final boolean failAfterChunk;
        private final AtomicInteger calls = new AtomicInteger();

        private StubModel(String name, boolean fail, boolean failAfterChunk) {
            this.name = name;
            this.fail = fail;
            this.failAfterChunk = failAfterChunk;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                calls.incrementAndGet();
                ChatResponse response = new ChatResponse(name, List.of(), null, null, "stop");
                if (!fail) {
                    return Flux.just(response);
                }
                if (failAfterChunk) {
                    return Flux.concat(Flux.just(response),
                        Flux.error(new IllegalStateException("mid-stream-" + name)));
                }
                return Flux.error(new IllegalStateException("before-stream-" + name));
            });
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public int getContextWindowSize() {
            return 8192;
        }
    }
}
