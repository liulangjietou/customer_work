package com.richard.fyoung.customerchannel;

import com.richard.fyoung.customerwork.observability.StudioConfigurer;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.admin.registry.AgentDescriptor;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * customer-channel 集成冒烟测试：验证 admin 控制台上下文加载，且本项目客服 Agent 被
 * {@link AgentRegistry} 自动接管（Agent / Model / Toolkit / AgentStateStore Bean 装配齐全）。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest
class CustomerWebIntegrationTest {

    @Autowired
    private AgentRegistry agentRegistry;

    @Autowired
    private Agent customerServiceAgent;

    @Autowired
    private Model chatModel;

    @Autowired
    private Toolkit customerToolkit;

    @Autowired
    private AgentStateStore agentStateStore;

    @Autowired
    private ChatCompletionsController chatCompletionsController;

    @Autowired
    private AguiMvcController aguiMvcController;

    @Autowired
    private StudioConfigurer studioConfigurer;

    @Autowired
    private DingTalkChannelConfigurer dingTalkChannelConfigurer;

    @Autowired
    private FeishuChannelConfigurer feishuChannelConfigurer;

    @Autowired
    private io.agentscope.extensions.channel.feishu.FeishuCallbackController feishuCallbackController;

    @Autowired
    private FeishuWebhookNotifier feishuWebhookNotifier;

    @Autowired
    private WeComChannelConfigurer weComChannelConfigurer;

    @Autowired
    private io.agentscope.extensions.channel.wecom.WeComCallbackController weComCallbackController;

    @Test
    void contextLoads_andCoreBeansWired() {
        assertNotNull(customerServiceAgent, "客服 Agent Bean 应装配");
        assertNotNull(chatModel, "模型 Bean 应装配");
        assertNotNull(agentStateStore, "状态存储 Bean 应装配");
        assertTrue(customerToolkit.getToolNames().contains("queryOrder"),
            "工具集应含业务工具: " + customerToolkit.getToolNames());
        assertNotNull(chatCompletionsController,
            "Chat Completions Web 控制器应装配（/v1/chat/completions）");
        assertNotNull(aguiMvcController,
            "AG-UI MVC 控制器应装配（/agui/run）");
        assertNotNull(studioConfigurer,
            "Studio 观测台接入应装配（StudioConfigurer，默认关闭）");
        assertNotNull(dingTalkChannelConfigurer,
            "钉钉 Channel 接入应装配（DingTalkChannelConfigurer，默认关闭）");
        assertNotNull(feishuChannelConfigurer,
            "飞书 Channel 接入应装配（FeishuChannelConfigurer，默认关闭）");
        assertNotNull(feishuCallbackController,
            "飞书事件回调控制器应装配（inbound /api/channels/feishu/{id}/callback）");
        assertNotNull(feishuWebhookNotifier,
            "飞书出站推送器应装配（outbound /push/feishu）");
        assertNotNull(weComChannelConfigurer,
            "企业微信 Channel 接入应装配（WeComChannelConfigurer，默认关闭）");
        assertNotNull(weComCallbackController,
            "企业微信事件回调控制器应装配（inbound /api/channels/wecom/{id}/callback）");
    }

    @Test
    void agent_shouldBeRegisteredInAdminRegistry() {
        assertTrue(agentRegistry.size() >= 1, "AgentRegistry 应至少接管一个 Agent");

        List<String> names = agentRegistry.list().stream()
            .map(a -> AgentDescriptor.of(a).name())
            .toList();
        assertTrue(names.contains("CustomerServiceAgent"),
            "admin 控制台应接管本项目客服 Agent: " + names);
    }
}
