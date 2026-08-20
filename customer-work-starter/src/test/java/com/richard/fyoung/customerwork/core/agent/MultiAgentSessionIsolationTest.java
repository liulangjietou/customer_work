package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Agent 编排的<b>会话隔离</b>回归测试。
 *
 * <p><b>守的是什么 bug</b>：{@code /api/customer/consult} 曾用三个静态常量
 * {@code RuntimeContext}（{@code userId="multi-agent"}、{@code sessionId="consult"}）
 * 配合进程级缓存的专家 Agent 实例。框架 {@code ReActAgent} 在实例字段
 * {@code ConcurrentHashMap<String, AgentState> stateCache} 里按 {@code slotKey(userId, sessionId)}
 * 缓存对话历史，于是<b>所有用户恒定命中同一个槽位、共用一份 memory_messages</b>——
 * A 用户咨询的订单号与退款金额会作为历史上下文出现在 B 用户的下一次请求里。</p>
 *
 * <p>原有的 {@code MultiAgentOrchestratorTest} 照不出这个问题：它每个用例都
 * {@code new} 一个编排器，测试构造的形态里根本不存在共享。这个测试专门盯住两件事：
 * 上下文按会话区分，以及专家实例不被跨会话复用。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MultiAgentSessionIsolationTest {

    /**
     * 手写 stub 而非 Mockito mock：本类只验证上下文槽位与实例复用，全程不触发模型调用。
     *
     * <p>不用 mock 还有一个实际好处——这条 P0 回归测试因此不依赖 byte-buddy 的 agent 自附加机制，
     * 在受限执行环境（沙箱 / 禁止 self-attach 的 CI）里照样能跑。守住 P0 的测试不该挑环境。</p>
     */
    private final Model model = new Model() {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            throw new UnsupportedOperationException("本测试不应触发模型调用");
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }
    };

    private MultiAgentOrchestrator orchestrator() {
        return new MultiAgentOrchestrator(model, new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
    }

    /** 不同会话必须落在不同的框架状态槽位上——这是"历史不串号"的充要条件。 */
    @Test
    @DisplayName("不同 sessionId 产生不同的 RuntimeContext 槽位")
    void differentSessionsGetDifferentContextSlots() throws Exception {
        MultiAgentOrchestrator orch = orchestrator();
        Method contextFor = MultiAgentOrchestrator.class
            .getDeclaredMethod("contextFor", String.class, String.class);
        contextFor.setAccessible(true);

        var ctxA = (io.agentscope.core.agent.RuntimeContext) contextFor.invoke(orch, "u1:conv-1", "consult");
        var ctxB = (io.agentscope.core.agent.RuntimeContext) contextFor.invoke(orch, "u2:conv-9", "consult");

        assertNotEquals(ctxA.getSessionId(), ctxB.getSessionId(),
            "两个不同会话拿到了同一个 sessionId —— 框架会把它们的对话历史存进同一个 slot");
        assertTrue(ctxA.getSessionId().contains("u1:conv-1"), "会话标识应体现在 sessionId 里");
        assertTrue(ctxB.getSessionId().contains("u2:conv-9"), "会话标识应体现在 sessionId 里");
    }

    /** 同一会话的各编排阶段互不污染：分诊/归纳的中间轮次不该混进专家会诊的历史。 */
    @Test
    @DisplayName("同一会话内 consult / router / reducer 三阶段各用独立槽位")
    void stagesWithinOneSessionAreSeparated() throws Exception {
        MultiAgentOrchestrator orch = orchestrator();
        Method contextFor = MultiAgentOrchestrator.class
            .getDeclaredMethod("contextFor", String.class, String.class);
        contextFor.setAccessible(true);

        String consult = ((io.agentscope.core.agent.RuntimeContext)
            contextFor.invoke(orch, "u1:conv-1", "consult")).getSessionId();
        String router = ((io.agentscope.core.agent.RuntimeContext)
            contextFor.invoke(orch, "u1:conv-1", "router")).getSessionId();
        String reducer = ((io.agentscope.core.agent.RuntimeContext)
            contextFor.invoke(orch, "u1:conv-1", "reducer")).getSessionId();

        assertEquals(3, List.of(consult, router, reducer).stream().distinct().count(),
            "三个阶段应各有独立槽位，实际有重复：" + List.of(consult, router, reducer));
    }

    /**
     * 专家 Agent 不得跨调用复用同一实例。
     *
     * <p>框架把会话状态存在 Agent 的实例字段里，进程级复用同一批实例会让状态跨会话累积
     * （内存只增不减，且历史串号）。原实现用 {@code cachedSpecialists} 做了这件事，
     * 并在注释里断言"Agent 无状态可安全复用"——那句话是错的。</p>
     */
    @Test
    @DisplayName("专家 Agent 每次新建，不做进程级实例复用")
    void specialistsAreNotCachedAcrossCalls() {
        MultiAgentOrchestrator orch = orchestrator();

        List<ReActAgent> first = orch.buildSpecialists();
        List<ReActAgent> second = orch.buildSpecialists();

        assertEquals(3, first.size());
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertNotSame(first.get(i), second.get(i),
                "第 " + i + " 个专家被复用了同一个实例 —— 框架会在它的 stateCache 里跨会话累积对话状态");
        }
    }
}
