package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** /consult 的 Jev 中车道：高置信直路由；低置信 / other / 不可用一律不采信（落到 LLM 慢车道）。 */
class MultiAgentJevLaneTest {

    /** 没有任何关键词命中的句子，确保不被快车道截走。 */
    private static final String NEUTRAL = "帮我看看这个";

    private MultiAgentOrchestrator orchestrator(JevTestSupport.StubClient client) {
        MultiAgentOrchestrator orch = new MultiAgentOrchestrator(mock(Model.class), new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
        orch.setJevDecisionService(JevTestSupport.service(client));
        return orch;
    }

    @Test
    @DisplayName("高置信意图：只挑认领该意图的专家")
    void highConfidenceRoutesToSingleExpert() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("order", 0.9);
        MultiAgentOrchestrator orch = orchestrator(client);

        List<ReActAgent> picked = orch.selectExperts("s1", NEUTRAL, orch.buildSpecialists()).block();

        assertEquals(1, picked.size());
        assertEquals("OrderExpert", picked.get(0).getName());
        assertEquals(1, client.calls());
    }

    @Test
    @DisplayName("关键词快车道仍然优先于 Jev")
    void fastLaneStillWinsBeforeJev() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("consult", 0.99);
        MultiAgentOrchestrator orch = orchestrator(client);

        List<ReActAgent> picked = orch.selectExperts("s1", "我要退款", orch.buildSpecialists()).block();

        assertEquals("AfterSalesExpert", picked.get(0).getName());
        assertEquals(0, client.calls());
    }

    @Test
    @DisplayName("低置信、other、Jev 不可用：不采信，退化为广播全部专家")
    void lowConfidenceOtherAndUnavailableAreNotTrusted() {
        for (JevTestSupport.StubClient client : List.of(JevTestSupport.calmTurn("order", 0.3),
            JevTestSupport.calmTurn("other", 0.99), JevTestSupport.unavailable(), JevTestSupport.failing())) {
            MultiAgentOrchestrator orch = orchestrator(client);
            // 未采信 → 落到 LLM 分诊；mock 模型下分诊失败，退化为广播全部专家（宁可多问不漏答）
            List<ReActAgent> picked = orch.selectExperts("s1", NEUTRAL, orch.buildSpecialists()).block();
            assertEquals(3, picked.size());
            assertEquals(1, client.calls());
        }
    }
}
