package com.richard.fyoung.customerchannel;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.channel.wecom.WeComChannel;
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
 * 企业微信 Channel 接入器（AgentScope 2.0 Channel · 企业微信 · 收到用户消息）。
 *
 * <p>把客服 {@link ReActAgent} 包成 {@link HarnessAgent} 并挂上 {@link WeComChannel}（应用 + 回调）：
 * 企业微信把用户消息事件 POST 到 {@code /api/channels/wecom/{channelId}/callback}（由
 * {@code WeComCallbackController} 接收，见 {@code CustomerWebAgentConfig} 注册的 Bean），经 channel 派发给 agent，
 * 回复经企业微信 API 下发。channel 启动时注册进 {@code WeComChannelRegistry} 供回调控制器查找。</p>
 *
 * <p>由 {@code customer-channel.channel.wecom.enabled=true} 启用；默认关闭。凭证/连接问题被兜底，不影响应用启动。
 * <b>完整收发需企业微信自建应用回调地址指向公网可达的上面地址</b>（部署或内网穿透）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class WeComChannelConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WeComChannelConfigurer.class);

    private final ReActAgent customerServiceAgent;
    private final AgentStateStore agentStateStore;
    private final WeComProperties properties;

    private volatile HarnessAgent harnessAgent;
    private volatile WeComChannel channel;

    public WeComChannelConfigurer(ReActAgent customerServiceAgent,
                                  AgentStateStore agentStateStore,
                                  WeComProperties properties) {
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
            raw.put("corpId", properties.getCorpId());
            raw.put("agentId", properties.getAgentId());
            raw.put("secret", properties.getSecret());
            raw.put("token", properties.getToken());
            raw.put("encodingAesKey", properties.getEncodingAesKey());

            channel = WeComChannel.fromProperties(channelId, ChannelConfig.of(channelId), raw);
            harnessAgent.channel(channel);   // 挂到 HarnessAgent gateway
            channel.start();                 // 注册进 WeComChannelRegistry，回调控制器据此派发
            log.info("[WeCom] channel started, inbound callback=/api/channels/wecom/{}/callback", channelId);
        } catch (Exception e) {
            log.error("[WeCom] start failed (ignored), code={}", "WECOM_START_ERROR", e);
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
            log.error("[WeCom] stop failed (ignored), code={}", "WECOM_STOP_ERROR", e);
        }
    }
}
