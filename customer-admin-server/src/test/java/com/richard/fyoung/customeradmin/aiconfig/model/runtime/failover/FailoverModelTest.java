package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FailoverModel} 单测：主成功不碰备 / 主失败切备 / 熔断跳过主 / 全熔断兜底 / 全失败抛错。
 * {@link Model} 用简单 stub 实现，{@code stream} 订阅计数用于观测实际尝试了哪些候选。
 * @author owlzhangfq@gmail.com
 */
class FailoverModelTest {

    /** 每次订阅计数 + 可选失败的桩模型；{@code getModelName}/响应 id 均为构造名，便于区分来源。 */
    private static final class StubModel implements Model {
        private final String name;
        private final boolean fail;
        private final AtomicInteger subscribeCount = new AtomicInteger();

        StubModel(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                subscribeCount.incrementAndGet();
                if (fail) {
                    return Flux.error(new RuntimeException("boom-" + name));
                }
                return Flux.just(new ChatResponse(name, List.of(), null, null, "stop"));
            });
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private ModelCircuitBreakerRegistry newRegistry(int threshold, int openSeconds) {
        AdminModelFailoverProperties props = new AdminModelFailoverProperties();
        props.setFailureThreshold(threshold);
        props.setOpenDurationSeconds(openSeconds);
        return new ModelCircuitBreakerRegistry(props);
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
            newRegistry(3, 60));

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
            newRegistry(3, 60));

        List<ChatResponse> out = run(model);

        assertEquals("b", out.get(0).getId());
        assertEquals(1, primary.subscribeCount.get());
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void openBreaker_shouldSkipPrimary() {
        ModelCircuitBreakerRegistry registry = newRegistry(1, 60);
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
        ModelCircuitBreakerRegistry registry = newRegistry(1, 60);
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
            newRegistry(3, 60));

        assertThrows(RuntimeException.class, () -> run(model));
        assertEquals(1, primary.subscribeCount.get());
        assertEquals(1, backup.subscribeCount.get());
    }

    @Test
    void getModelName_shouldDelegateToPrimary() {
        StubModel primary = new StubModel("primary-model", false);
        StubModel backup = new StubModel("backup-model", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            newRegistry(3, 60));

        assertEquals("primary-model", model.getModelName());
    }
}
