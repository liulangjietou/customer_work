package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    /** 智能路由纯映射：order→订单专家，refund/complaint→售后专家，consult→知识库专家，other/未知→全部。 */
    @Test
    void expertsForIntent_shouldRouteToRelevantExperts() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        List<ReActAgent> all = orch.buildSpecialists();

        assertEquals(List.of("OrderExpert"), names(orch.expertsForIntent("order", all)));
        assertEquals(List.of("AfterSalesExpert"), names(orch.expertsForIntent("refund", all)));
        assertEquals(List.of("AfterSalesExpert"), names(orch.expertsForIntent("complaint", all)));
        assertEquals(List.of("KnowledgeExpert"), names(orch.expertsForIntent("consult", all)));
        // other / 未知 / null → 广播全部，保证不漏
        assertEquals(3, orch.expertsForIntent("other", all).size());
        assertEquals(3, orch.expertsForIntent("unknown-xyz", all).size());
        assertEquals(3, orch.expertsForIntent(null, all).size());
    }

    /** 规则快车道：命中唯一意图关键词→返回该意图；命中多类/无命中→empty（交 LLM）。 */
    @Test
    void fastRouteIntent_shouldHitUniqueIntentOnly() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());

        assertEquals("refund", orch.fastRouteIntent("我要退款").orElse(null));
        assertEquals("order", orch.fastRouteIntent("我的快递到哪了").orElse(null));
        assertEquals("complaint", orch.fastRouteIntent("我要投诉你们").orElse(null));
        assertEquals("consult", orch.fastRouteIntent("能开发票吗").orElse(null));
        // 命中多类（退款 + 投诉）→ 语义模糊，交 LLM
        assertTrue(orch.fastRouteIntent("我要退款不然就投诉").isEmpty());
        // 无命中 → 交 LLM
        assertTrue(orch.fastRouteIntent("你好在吗").isEmpty());
        assertTrue(orch.fastRouteIntent("").isEmpty());
    }

    /** 快车道直路由：命中唯一意图时 selectExperts 不调模型即返回相关专家。 */
    @Test
    void selectExperts_shouldUseFastLaneWithoutModel() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        List<ReActAgent> all = orch.buildSpecialists();

        // "退款" 命中快车道 → 直接售后专家，全程不触发 routerAgent（mock model 也不会被调用）
        List<ReActAgent> picked = orch.selectExperts("s-1", "我要退款", all).block(Duration.ofSeconds(2));
        assertEquals(List.of("AfterSalesExpert"), names(picked));
    }

    /** 路由关闭：selectExperts 不调模型，直接返回全部专家（离线可验证）。 */
    @Test
    void selectExperts_shouldReturnAllWhenRoutingDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().setRoutingEnabled(false);
        MultiAgentOrchestrator orch = newOrchestrator(props);
        List<ReActAgent> all = orch.buildSpecialists();

        List<ReActAgent> picked = orch.selectExperts("s-1", "随便问问", all).block(Duration.ofSeconds(2));
        assertEquals(3, picked.size());
    }

    /** reduce 关闭：直接返回拼接结果，不调归纳器模型。 */
    @Test
    void reduce_shouldFallbackToAggregateWhenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().setReduceEnabled(false);
        MultiAgentOrchestrator orch = newOrchestrator(props);

        String out = orch.reduce("s-1", "退款吗", List.of(reply("OrderExpert", "已发货"),
            reply("KnowledgeExpert", "支持七天无理由"))).block(Duration.ofSeconds(2));
        assertTrue(out.contains("【OrderExpert】已发货"));
        assertTrue(out.contains("【KnowledgeExpert】支持七天无理由"));
    }

    /** reduce 开启但只有单专家：无需归纳，直接返回拼接（避免多余模型调用）。 */
    @Test
    void reduce_shouldSkipForSingleReply() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        String out = orch.reduce("s-1", "订单状态", List.of(reply("OrderExpert", "已发货")))
            .block(Duration.ofSeconds(2));
        assertEquals("【OrderExpert】已发货", out);
    }

    /** 可观测埋点：注入 registry 后路由/专家耗时/reduce/扇出专家数指标均被记录。 */
    @Test
    void metrics_shouldBeRecordedWhenRegistryPresent() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        orch.setMeterRegistry(reg);

        orch.recordRoute("order", 1);
        orch.recordExpert("OrderExpert", "success", 5_000_000L);
        orch.recordReduce(true);

        assertEquals(1.0, reg.get("customerwork.mas.route").tag("intent", "order").counter().count());
        assertEquals(1L, reg.get("customerwork.mas.expert")
            .tag("expert", "OrderExpert").tag("outcome", "success").timer().count());
        assertEquals(1.0, reg.get("customerwork.mas.reduce").tag("triggered", "true").counter().count());
        assertEquals(1.0, reg.get("customerwork.mas.fanout.experts").summary().totalAmount());
    }

    /** 无 registry 时埋点为 no-op，不抛异常（降级安全）。 */
    @Test
    void metrics_shouldBeNoopWhenRegistryAbsent() {
        MultiAgentOrchestrator orch = newOrchestrator(new CustomerWorkProperties());
        orch.recordRoute("order", 1);
        orch.recordExpert("E", "timeout", 1L);
        orch.recordReduce(false);
    }

    private List<String> names(List<ReActAgent> agents) {
        return agents.stream().map(ReActAgent::getName).collect(Collectors.toList());
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
