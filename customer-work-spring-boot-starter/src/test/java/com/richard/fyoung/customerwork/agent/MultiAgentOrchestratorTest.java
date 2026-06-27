package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 多 Agent 编排器单测（多 Agent 与分布式协作，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code Pipelines} 已移除，编排改用 Reactor（见 {@code MultiAgentOrchestrator}）。
 * 这里用离线断言校验专家装配与聚合逻辑（真实串/并行需模型，由集成测试覆盖）。</p>
 * @author owlzhangfq@gmail.com
 */
class MultiAgentOrchestratorTest {

    private final Model model = mock(Model.class);

    @Test
    void buildSpecialists_shouldCreateThreeNamedExperts() {
        MultiAgentOrchestrator orch = new MultiAgentOrchestrator(model, new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
        List<ReActAgent> specialists = orch.buildSpecialists();

        assertEquals(3, specialists.size());
        List<String> names = specialists.stream().map(ReActAgent::getName).toList();
        assertTrue(names.contains("OrderExpert"));
        assertTrue(names.contains("AfterSalesExpert"));
        assertTrue(names.contains("KnowledgeExpert"));
    }

    @Test
    void aggregate_shouldJoinWithExpertNames() {
        MultiAgentOrchestrator orch = new MultiAgentOrchestrator(model, new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
        String merged = orch.aggregate(List.of(
            Msg.builder().role(MsgRole.ASSISTANT).name("OrderExpert")
                .content(TextBlock.builder().text("已发货").build()).build(),
            Msg.builder().role(MsgRole.ASSISTANT).name("KnowledgeExpert")
                .content(TextBlock.builder().text("支持七天无理由").build()).build()));

        assertTrue(merged.contains("【OrderExpert】已发货"));
        assertTrue(merged.contains("【KnowledgeExpert】支持七天无理由"));
    }

    private MultiAgentOrchestrator newOrchestrator(CustomerWorkProperties props) {
        return new MultiAgentOrchestrator(model, props,
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
    }

    /**
     * 真并行验证：3 个各阻塞 200ms 的任务，记录运行期峰值并发度。
     * fanout 经 subscribeOn(boundedElastic) 后应≥2（实际并发），证明不是"并行写法、串行执行"。
     */
    @Test
    void fanout_shouldRunTasksConcurrently() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<Mono<Msg>> tasks = IntStream.range(0, 3)
            .mapToObj(i -> blockingTask("E" + i, running, peak, 200))
            .collect(Collectors.toList());

        List<Msg> replies = orch.fanout(tasks, 8).block(Duration.ofSeconds(5));

        assertEquals(3, replies.size());
        assertTrue(peak.get() >= 2, "fanout 应真并发执行，峰值并发度=" + peak.get());
    }

    /** 限流验证：concurrency=1 时即便用并行组合子，峰值并发度也应被压到 1（退化为串行）。 */
    @Test
    void fanout_shouldRespectConcurrencyLimit() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<Mono<Msg>> tasks = IntStream.range(0, 3)
            .mapToObj(i -> blockingTask("E" + i, running, peak, 80))
            .collect(Collectors.toList());

        orch.fanout(tasks, 1).block(Duration.ofSeconds(5));

        assertEquals(1, peak.get(), "concurrency=1 应把峰值并发度限制为 1");
    }

    /** 错误隔离验证：单个任务抛错不应让整体失败，其余结果照常聚合。 */
    @Test
    void fanout_shouldIsolateFailures() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());

        List<Mono<Msg>> tasks = new ArrayList<>();
        tasks.add(Mono.just(reply("Ok", "正常")));
        tasks.add(Mono.<Msg>error(new RuntimeException("boom"))
            .onErrorResume(e -> Mono.just(reply("Bad", "[暂不可用]"))));

        List<Msg> replies = orch.fanout(tasks, 8).block(Duration.ofSeconds(5));

        assertEquals(2, replies.size());
        String merged = orch.aggregate(replies);
        assertTrue(merged.contains("【Ok】正常"));
        assertTrue(merged.contains("【Bad】[暂不可用]"));
    }

    private Mono<Msg> blockingTask(String name, AtomicInteger running, AtomicInteger peak, long sleepMs) {
        return Mono.fromCallable(() -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(sleepMs);
            } finally {
                running.decrementAndGet();
            }
            return reply(name, "done");
        });
    }

    private Msg reply(String name, String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name(name)
            .content(TextBlock.builder().text(text).build()).build();
    }
}
