package com.richard.fyoung.customerchannel;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉 Channel 接入器（AgentScope 2.0 Channel · 钉钉）。
 *
 * <p>把客服 Agent 接入钉钉机器人：用 {@link HarnessAgent.Builder#fromAgent} 把客服 {@link ReActAgent}
 * 包成 {@link HarnessAgent}，再挂上 {@link DingTalkChannel}（钉钉 <b>Stream 模式</b>，主动出站 WebSocket 连接
 * 钉钉网关，无需公网回调）。用户在钉钉群 @ 机器人即可与客服 agent 对话，回复经同一通道下发。</p>
 *
 * <p>由 {@code customer-channel.channel.dingtalk.enabled=true} 且配齐 {@code appKey/appSecret/robotCode} 时启用；
 * 默认关闭。连接异步建立，失败被兜底，不影响应用启动与其它前端（admin/chat/AG-UI/Studio）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DingTalkChannelConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannelConfigurer.class);

    private final ReActAgent customerServiceAgent;
    private final AgentStateStore agentStateStore;
    private final DingTalkProperties properties;

    private volatile HarnessAgent harnessAgent;
    private volatile DingTalkChannel channel;

    public DingTalkChannelConfigurer(ReActAgent customerServiceAgent,
                                     AgentStateStore agentStateStore,
                                     DingTalkProperties properties) {
        this.customerServiceAgent = customerServiceAgent;
        this.agentStateStore = agentStateStore;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && properties.hasCredentials();
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.hasCredentials()) {
            log.info("[DingTalk] enabled but credentials missing (appKey/appSecret/robotCode), skip");
            return;
        }
        try {
            // 客服 ReActAgent 包成 HarnessAgent（Channel 经 HarnessAgent 的 gateway 路由）
            harnessAgent = HarnessAgent.Builder.fromAgent(customerServiceAgent)
                .stateStore(agentStateStore)
                .defaultSessionId(ChannelAccessConstants.CHANNEL_TYPE_DINGTALK)
                .build();

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("appKey", properties.getAppKey());
            raw.put("appSecret", properties.getAppSecret());
            raw.put("robotCode", properties.getRobotCode());

            channel = DingTalkChannel.fromProperties(ChannelAccessConstants.CHANNEL_TYPE_DINGTALK, ChannelConfig.of(ChannelAccessConstants.CHANNEL_TYPE_DINGTALK), raw);
            harnessAgent.channel(channel);   // 挂到 HarnessAgent gateway
            channel.start();                 // Stream 模式：出站连接钉钉网关
            log.info("[DingTalk] channel started (stream mode), robotCode={}", properties.getRobotCode());
        } catch (Exception e) {
            log.error("[DingTalk] start failed (ignored), code={}", "DINGTALK_START_ERROR", e);
        }
    }

    @PreDestroy
    public void stop() {
        try {
            if (channel != null) {
                channel.stop();
            }
            if (harnessAgent != null) {
                harnessAgent.close();
            }
        } catch (Exception e) {
            log.error("[DingTalk] stop failed (ignored), code={}", "DINGTALK_STOP_ERROR", e);
        }
    }
}
