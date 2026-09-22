package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Jev 相关单测的公共桩。
 *
 * <p><b>RuntimeContext 必须用真实实例</b>：一轮只调一次 Jev、onAgent 把判定交给 onReasoning，
 * 都依赖它真实的 put/get。mock 掉它等于绕开了被测的核心机制。</p>
 */
public final class JevTestSupport {

    private JevTestSupport() {
    }

    /** 可编程的传输层桩，记录每次提问。 */
    public static final class StubClient implements SystemOneClient {
        private final Function<Map<String, SystemOneQuestion>, Mono<SystemOneResult>> responder;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> states = new ArrayList<>();

        public StubClient(Function<Map<String, SystemOneQuestion>, Mono<SystemOneResult>> responder) {
            this.responder = responder;
        }

        @Override
        public Mono<SystemOneResult> ask(String state, Map<String, SystemOneQuestion> questions, Duration timeout) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                synchronized (states) {
                    states.add(state);
                }
                return responder.apply(questions);
            });
        }

        public int calls() {
            return calls.get();
        }

        public List<String> states() {
            synchronized (states) {
                return List.copyOf(states);
            }
        }
    }

    /** Jev 不可用：一律返回空（传输层把超时、熔断、错误都表现为空）。 */
    public static StubClient unavailable() {
        return new StubClient(questions -> Mono.empty());
    }

    /** 入站决策：给定意图与情绪档位分布。 */
    public static StubClient turn(String intent, double intentConfidence, double escalationScore,
                                  double escalationConfidence, Map<Integer, Double> probabilities) {
        return new StubClient(questions -> Mono.just(new SystemOneResult("jev-test", Map.of(
            JevDecisionService.Q_INTENT, new SystemOneAnswer.Choice(intent, intentConfidence),
            JevDecisionService.Q_ESCALATION, new SystemOneAnswer.Score(escalationScore, escalationConfidence,
                probabilities)), 12)));
    }

    /** 情绪平稳的入站决策。 */
    public static StubClient calmTurn(String intent, double intentConfidence) {
        return turn(intent, intentConfidence, 0.1, 0.9, Map.of(0, 0.9, 1, 0.1, 2, 0.0, 3, 0.0));
    }

    /** 高置信处于最高档（强烈愤怒 / 要求人工）。 */
    public static StubClient furiousTurn() {
        return turn("complaint", 0.95, 2.95, 0.93, Map.of(0, 0.0, 1, 0.0, 2, 0.05, 3, 0.95));
    }

    /** 明显不满但未到最高档。 */
    public static StubClient upsetTurn() {
        return turn("complaint", 0.95, 2.1, 0.7, Map.of(0, 0.0, 1, 0.1, 2, 0.7, 3, 0.2));
    }

    /** 是/否判定：对任何 noul 问题都返回同一概率。 */
    public static StubClient noul(double probability) {
        return new StubClient(questions -> {
            String id = questions.keySet().iterator().next();
            return Mono.just(new SystemOneResult("jev-test", Map.of(id, new SystemOneAnswer.Noul(probability)), 8));
        });
    }

    /** 传输层桩抛错：验证调用方不会被它打断（真实实现永不抛错，这是防自定义实现越界）。 */
    public static StubClient failing() {
        return new StubClient(questions -> Mono.error(new IllegalStateException("boom")));
    }

    public static JevDecisionService service(SystemOneClient client) {
        return new JevDecisionService(client, new TypeSafeProperties(), null);
    }

    public static JevDecisionService service(SystemOneClient client, TypeSafeProperties properties) {
        return new JevDecisionService(client, properties, null);
    }

    public static RuntimeContext ctx() {
        return RuntimeContext.builder().sessionId("u1:conv-1").build();
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
