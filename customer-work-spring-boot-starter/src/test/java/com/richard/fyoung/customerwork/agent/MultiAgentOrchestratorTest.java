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

import java.util.List;

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
}
