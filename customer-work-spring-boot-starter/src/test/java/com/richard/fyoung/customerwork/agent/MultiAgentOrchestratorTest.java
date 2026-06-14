package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.pipeline.Pipelines;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 多 Agent 编排器单测（多 Agent 与分布式协作）。
 *
 * <p>专家 Agent 的真实推理需模型，这里：(1) 用 mock 模型校验专家装配；
 * (2) 用离线 {@link EchoAgent} 校验 Pipeline 串/并行编排与聚合逻辑。</p>
 * @author owlzhangfq@gmail.com
 */
class MultiAgentOrchestratorTest {

    private final Model model = mock(Model.class);

    @Test
    void buildSpecialists_shouldCreateThreeNamedExperts() {
        MultiAgentOrchestrator orch = new MultiAgentOrchestrator(model, new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
        List<AgentBase> specialists = orch.buildSpecialists();

        assertEquals(3, specialists.size());
        List<String> names = specialists.stream().map(AgentBase::getName).toList();
        assertTrue(names.contains("OrderExpert"));
        assertTrue(names.contains("AfterSalesExpert"));
        assertTrue(names.contains("KnowledgeExpert"));
    }

    @Test
    void fanout_shouldCollectAllExpertReplies() {
        List<AgentBase> experts = List.of(new EchoAgent("A"), new EchoAgent("B"), new EchoAgent("C"));
        Msg q = userMsg("查询订单");

        StepVerifier.create(Pipelines.fanout(experts, q))
            .assertNext(replies -> assertEquals(3, replies.size(), "fanout 应收集所有专家回复"))
            .verifyComplete();
    }

    @Test
    void sequential_shouldChainThroughAgents() {
        List<AgentBase> experts = List.of(new EchoAgent("A"), new EchoAgent("B"));

        StepVerifier.create(Pipelines.sequential(experts, userMsg("hi")))
            .assertNext(result -> assertTrue(result.getTextContent().contains("B 处理"),
                "sequential 最终结果应由最后一个 Agent 产出: " + result.getTextContent()))
            .verifyComplete();
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

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }
}
