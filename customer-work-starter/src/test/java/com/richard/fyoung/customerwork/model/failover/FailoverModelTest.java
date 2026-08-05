package com.richard.fyoung.customerwork.model.failover;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FailoverModel} 单测：主成功不碰备 / 主失败切备 / 熔断跳过主 / 全熔断兜底 / 全失败抛错 /
 * 单候选照常工作，以及"首分片之后失败要不要继续切候选"两种语义。
 *
 * <p>由 customer-admin-server 同名测试平移（构造入参改显式阈值），并合入原
 * {@code FallbackChatModelTest} 的首分片语义用例——客服主链路的兜底能力已由本类承载。</p>
 * @author owlzhangfq@gmail.com
 */
class FailoverModelTest {

    /** 每次订阅计数 + 可选失败的桩模型；{@code getModelName}/响应 id 均为构造名，便于区分来源。 */
    private static final class StubModel implements Model {
        private final String name;
        private final boolean fail;
        /** true=先吐一个分片再失败（模拟流中途失败）；false=订阅即失败。 */
        private final boolean failAfterFirstChunk;
        private final AtomicInteger subscribeCount = new AtomicInteger();

        StubModel(String name, boolean fail) {
            this(name, fail, false);
        }

        StubModel(String name, boolean fail, boolean failAfterFirstChunk) {
            this.name = name;
            this.fail = fail;
            this.failAfterFirstChunk = failAfterFirstChunk;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                subscribeCount.incrementAndGet();
                if (fail && failAfterFirstChunk) {
                    return Flux.concat(Flux.just(response()),
                        Flux.error(new RuntimeException("mid-stream-" + name)));
                }
                if (fail) {
                    return Flux.error(new RuntimeException("boom-" + name));
                }
                return Flux.just(response());
            });
        }

        private ChatResponse response() {
            return new ChatResponse(name, List.of(), null, null, "stop");
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public int getContextWindowSize() {
            return 128;
        }
    }

    private List<ChatResponse> run(FailoverModel model) {
        return model.stream(List.<Msg>of(), null, null).collectList().block();
    }

    @Test
    void primarySuccess_shouldNotTouchBackup() {
        StubModel primary = new StubModel("p", false);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60));

        List<ChatResponse> out = run(model);

        assertEquals("p", out.get(0).getId());
        assertEquals(1, primary.subscribeCount.get());
        assertEquals(0, backup.subscribeCount.get());
    }

    @Test
    void primaryFailure_shouldSwitchToBackup() {
        StubModel primary = new StubModel("p", true);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60));

        List<ChatResponse> out = run(model);

        assertEquals("b", out.get(0).getId());
        assertEquals(1, primary.subscribeCount.get());
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void openBreaker_shouldSkipPrimary() {
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(1, 60);
        registry.recordFailure(1L); // 阈值 1，主模型立即熔断
        StubModel primary = new StubModel("p", false);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)), registry);

        List<ChatResponse> out = run(model);

        assertEquals("b", out.get(0).getId());
        assertEquals(0, primary.subscribeCount.get()); // 主被熔断跳过，未订阅
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void allBreakersOpen_shouldFallbackToFullList() {
        ModelCircuitBreakerRegistry registry = new ModelCircuitBreakerRegistry(1, 60);
        registry.recordFailure(1L);
        registry.recordFailure(2L); // 主备全部熔断
        StubModel primary = new StubModel("p", false);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)), registry);

        List<ChatResponse> out = run(model);

        // 全熔断退化为全量候选，仍从主开始尝试，不拒绝服务
        assertEquals("p", out.get(0).getId());
        assertEquals(1, primary.subscribeCount.get());
    }

    @Test
    void allCandidatesFail_shouldPropagateLastError() {
        StubModel primary = new StubModel("p", true);
        StubModel backup = new StubModel("b", true);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60));

        assertThrows(RuntimeException.class, () -> run(model));
        assertEquals(1, primary.subscribeCount.get());
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void singleCandidate_shouldWorkNormally() {
        StubModel only = new StubModel("only", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, only)), new ModelCircuitBreakerRegistry(3, 60));

        List<ChatResponse> out = run(model);

        assertEquals("only", out.get(0).getId());
        assertEquals(1, only.subscribeCount.get());
    }

    @Test
    void emptyCandidates_shouldFastFail() {
        assertThrows(IllegalArgumentException.class,
            () -> new FailoverModel(List.of(), new ModelCircuitBreakerRegistry(3, 60)));
    }

    @Test
    void midStreamFailure_shouldSwitchToBackup_whenMidStreamFailoverEnabled() {
        StubModel primary = new StubModel("p", true, true);
        StubModel backup = new StubModel("b", false);
        // 默认构造：流中途失败也切下一候选（动态智能体运行时语义）
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60));

        List<ChatResponse> out = run(model);

        // 主的首个分片 + 备的完整输出（该语义下允许重复，换取拿到完整回答）
        assertEquals(2, out.size());
        assertEquals("p", out.get(0).getId());
        assertEquals("b", out.get(1).getId());
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void midStreamFailure_shouldPropagateError_whenMidStreamFailoverDisabled() {
        StubModel primary = new StubModel("p", true, true);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60), false);

        StepVerifier.create(model.stream(List.<Msg>of(), null, null))
            .expectNextMatches(resp -> "p".equals(resp.getId()))
            .expectErrorMatches(e -> e.getMessage() != null && e.getMessage().contains("mid-stream"))
            .verify();

        // 首分片已发出后失败，不应再调用备模型（否则前后半段拼接错乱）
        assertEquals(0, backup.subscribeCount.get());
    }

    @Test
    void beforeFirstChunkFailure_shouldSwitchToBackup_evenWhenMidStreamFailoverDisabled() {
        StubModel primary = new StubModel("p", true);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60), false);

        List<ChatResponse> out = run(model);

        assertEquals(1, out.size());
        assertEquals("b", out.get(0).getId());
    }

    @Test
    void identityAndCapabilities_shouldDelegateToPrimary() {
        StubModel primary = new StubModel("primary-model", false);
        StubModel backup = new StubModel("backup-model", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            new ModelCircuitBreakerRegistry(3, 60));

        assertEquals("primary-model", model.getModelName());
        assertEquals(128, model.getContextWindowSize());
    }
}
