package com.richard.fyoung.customerweb;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
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
 * 飞书 Channel 接入器（AgentScope 2.0 Channel · 飞书 · inbound 收到用户消息）。
 *
 * <p>把客服 {@link ReActAgent} 包成 {@link HarnessAgent} 并挂上 {@link FeishuChannel}（应用 + 事件回调）：
 * 飞书把用户消息事件 POST 到 {@code /api/channels/feishu/{channelId}/callback}（由
 * {@code FeishuCallbackController} 接收，见 {@code CustomerWebAgentConfig} 注册的 Bean），经 channel 派发给 agent，
 * 回复经飞书 API 下发。channel 启动时注册进 {@code FeishuChannelRegistry} 供回调控制器查找。</p>
 *
 * <p>由 {@code customer-web.channel.feishu.enabled=true} 启用；默认关闭。连接 / 凭证问题被兜底，不影响应用启动。
 * <b>完整 inbound 需飞书应用事件订阅指向公网可达的回调地址</b>（部署或内网穿透）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class FeishuChannelConfigurer {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannelConfigurer.class);

    private final ReActAgent customerServiceAgent;
    private final AgentStateStore agentStateStore;
    private final FeishuProperties properties;

    private volatile HarnessAgent harnessAgent;
    private volatile FeishuChannel channel;

    public FeishuChannelConfigurer(ReActAgent customerServiceAgent,
                                   AgentStateStore agentStateStore,
                                   FeishuProperties properties) {
        this.customerServiceAgent = customerServiceAgent;
        this.agentStateStore = agentStateStore;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String channelId = properties.getChannelId();
            harnessAgent = HarnessAgent.Builder.fromAgent(customerServiceAgent)
                .stateStore(agentStateStore)
                .defaultSessionId(channelId)
                .build();

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("appId", properties.getAppId());
            raw.put("appSecret", properties.getAppSecret());
            raw.put("encryptKey", properties.getEncryptKey());
            raw.put("verificationToken", properties.getVerificationToken());

            channel = FeishuChannel.fromProperties(channelId, ChannelConfig.of(channelId), raw);
            harnessAgent.channel(channel);   // 挂到 HarnessAgent gateway
            channel.start();                 // 注册进 FeishuChannelRegistry，回调控制器据此派发
            log.info("[Feishu] channel started, inbound callback=/api/channels/feishu/{}/callback", channelId);
        } catch (Exception e) {
            log.error("[Feishu] start failed (ignored), code={}", "FEISHU_START_ERROR", e);
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
            log.error("[Feishu] stop failed (ignored), code={}", "FEISHU_STOP_ERROR", e);
        }
    }
}
