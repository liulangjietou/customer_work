package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.core.middleware.MiddlewareOrders;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话状态冲突策略与最终答复过滤的装配门禁（AgentScope 2.0.3 能力采纳）。
 *
 * <h3>为什么这两件事要一起守</h3>
 * <p>它们都是 2.0.3 新增、且都<b>只能在构建期设定</b>的东西：冲突策略是
 * {@code ReActAgent.Builder#conflictPolicy}，最终答复过滤是一个必须在 build 前挂上的中间件。
 * 构建期设定的东西一旦散落到多个建 Agent 的入口里各写一遍，就会重演本项目最顽固的那个缺陷形状
 * ——"能力只接在一条路径上"。因此两者都收敛在 {@link AgentGovernanceAssembler} 里，
 * 由本测试断言它确实生效。</p>
 *
 * <p>断言读的是 {@link ReActAgent#getConflictPolicy()} 与 {@link ReActAgent#getMiddlewares()}
 * 这两个框架公开的查询接口，即"建出来的 Agent 实际上是什么样"，
 * 而不是"装配器里有没有写那一行代码"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AgentStateConflictPolicyAssemblyTest {

    @Test
    @DisplayName("默认冲突策略是 OVERWRITE，与框架默认一致")
    void defaultPolicyIsOverwrite() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        assertEquals(ConflictPolicy.OVERWRITE, props.getAgent().getStateConflictPolicy(),
            "默认值变了就是行为变更：FAIL 会在并发写时中断用户对话，"
                + "改默认值前先看 customerwork.agent.state.conflicts 指标说明冲突到底多不多");
        assertEquals(ConflictPolicy.OVERWRITE, buildAgent(props).getConflictPolicy());
    }

    @Test
    @DisplayName("配置的冲突策略必须真的传到 Agent 上（三档逐一验证）")
    void configuredPolicyReachesAgent() {
        for (ConflictPolicy policy : ConflictPolicy.values()) {
            CustomerWorkProperties props = new CustomerWorkProperties();
            props.getAgent().setStateConflictPolicy(policy);

            assertEquals(policy, buildAgent(props).getConflictPolicy(),
                "配置了 " + policy + " 却没传到 Agent——装配器漏了 builder.conflictPolicy()，"
                    + "表现是配置项写了不生效，而不会报错");
        }
    }

    @Test
    @DisplayName("最终答复过滤默认关闭：C 端流式打字机效果不能被它吃掉")
    void finalAnswerFilterIsOffByDefault() {
        CustomerWorkProperties props = new CustomerWorkProperties();

        assertFalse(props.getAgent().isFinalAnswerFilterEnabled(),
            "默认值不得为 true——它要等 ModelCallEndEvent 才把整轮文本一次性放出，"
                + "打开就等于用户盯着空白等几秒再看到整段答复");
        assertFalse(hasFinalAnswerFilter(buildAgent(props)),
            "开关关着却挂上了最终答复过滤中间件");
    }

    @Test
    @DisplayName("开启后中间件挂得上，且稳定落在最内层")
    void finalAnswerFilterMountsInnermostWhenEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getAgent().setFinalAnswerFilterEnabled(true);
        ReActAgent agent = buildAgent(props);

        assertTrue(hasFinalAnswerFilter(agent), "开关开着却没挂上最终答复过滤中间件");

        // 框架语义：order 数值越大越靠外。这个中间件取框架默认值 1，
        // 而本项目全部治理中间件都在 50（CONTEXT_BUDGET，最内层）到 200 之间，
        // 因此它必然比它们都内——先由它决定这一轮的文本放不放，放出来的那份再依次经过
        // 自我纠错、脱敏、敏感词等出站处理。若哪天框架把它的 order 抬到 50 以上，
        // 顺序会翻转成"先脱敏再决定丢不丢"，白做一遍出站处理且不报任何错。
        //
        // 比较对象刻意限定为本项目的治理中间件：框架自己的内置中间件同样取默认值 1，
        // 与它们比先后没有意义，也不该由本项目来约束。
        int filterOrder = agent.getMiddlewares().stream()
            .filter(FinalAnswerFilterMiddleware.class::isInstance)
            .mapToInt(MiddlewareBase::order)
            .max()
            .orElseThrow();

        assertTrue(filterOrder < MiddlewareOrders.CONTEXT_BUDGET,
            "最终答复过滤的 order=" + filterOrder + " 已不小于本项目最内层治理中间件的 "
                + MiddlewareOrders.CONTEXT_BUDGET + "——出站处理顺序会被翻转");
    }

    private ReActAgent buildAgent(CustomerWorkProperties props) {
        ReActAgent.Builder builder = ReActAgent.builder()
            .name("policy-probe-agent")
            .sysPrompt("离线装配探针")
            .model(stubModel())
            .toolkit(new Toolkit());
        assembler(props).applyTo(builder);
        return builder.build();
    }

    private AgentGovernanceAssembler assembler(CustomerWorkProperties props) {
        return new AgentGovernanceAssembler(props, new TenantResolver(props), null, null);
    }

    private boolean hasFinalAnswerFilter(ReActAgent agent) {
        return agent.getMiddlewares().stream().anyMatch(FinalAnswerFilterMiddleware.class::isInstance);
    }

    /** 不发真实请求的离线模型：本测试只关心构建期装配结果。 */
    private Model stubModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok").build()))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
            }

            @Override
            public String getModelName() {
                return "stub-offline-model";
            }
        };
    }
}
