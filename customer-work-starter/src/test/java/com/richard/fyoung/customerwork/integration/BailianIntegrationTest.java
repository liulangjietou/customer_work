package com.richard.fyoung.customerwork.integration;

import com.richard.fyoung.customerwork.tool.OrderTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼平台真实集成测试（对接 DashScope，消耗真实额度）。
 *
 * <p>默认<b>不随 {@code mvn test} 执行</b>，以保证常规单测无需网络、稳定通过。
 * 需要真实联调时，设置环境变量后运行：</p>
 *
 * <pre>
 *   export RUN_BAILIAN_IT=true
 *   export DASHSCOPE_API_KEY=你的百炼密钥
 *   mvn test -Dtest=BailianIntegrationTest
 * </pre>
 *
 * <p>该测试构建真实 {@link DashScopeChatModel} + {@link ReActAgent}，发起一轮带工具调用的对话，
 * 验证端到端链路可在百炼平台跑通。</p>
 * @author owlzhangfq@gmail.com
 */
@EnabledIfEnvironmentVariable(named = "RUN_BAILIAN_IT", matches = "true")
class BailianIntegrationTest {

    private String apiKey() {
        String env = System.getenv("DASHSCOPE_API_KEY");
        if (env == null || env.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is required when RUN_BAILIAN_IT=true");
        }
        return env.trim();
    }

    private Model bailianModel() {
        return DashScopeChatModel.builder()
            .apiKey(apiKey())
            .modelName("qwen-plus")
            .stream(false)
            .build();
    }

    @Test
    void agent_shouldAnswerWithToolCall_onBailian() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools(new com.richard.fyoung.customerwork.tool.backend.MockOrderBackend()));

        ReActAgent agent = ReActAgent.builder()
            .name("it-agent")
            .sysPrompt("你是电商客服，遇到订单查询请调用订单工具后回答。")
            .model(bailianModel())
            .toolkit(toolkit)
            .maxIters(5)
            .build();

        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text("帮我查一下订单 20260613001 的状态").build())
            .build();

        RuntimeContext ctx = RuntimeContext.builder().userId("it").sessionId("it-bailian").build();
        Msg reply = agent.call(java.util.List.of(userMsg), ctx).block(Duration.ofSeconds(60));

        assertNotNull(reply, "百炼应返回非空回复");
        String text = reply.getTextContent();
        assertNotNull(text);
        assertTrue(!text.isBlank(), "回复文本不应为空");
    }
}
