package com.richard.fyoung.customerchannel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信 Channel 配置（{@code customer-channel.channel.wecom.*}）。
 *
 * <p>企业微信走"应用 + 回调"模型：inbound 由企业微信把事件 POST 到
 * {@code /api/channels/wecom/{channelId}/callback}（首次配置时 GET 做 URL 验证）；outbound 经企业微信 API 回复。
 * 需在企业微信管理后台创建自建应用，配置接收消息的回调地址，并填入 {@code corpId/agentId/secret} 与回调的
 * {@code token/encodingAesKey}。默认关闭。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "customer-channel.channel.wecom")
public class WeComProperties {

    /** 是否启用企业微信 Channel。默认关闭。 */
    private boolean enabled = false;
    /** 通道 ID（回调路径 /api/channels/wecom/{channelId}/callback 中的 channelId）。 */
    private String channelId = "wecom";
    /** 企业 ID（CorpID）。 */
    private String corpId = "";
    /** 自建应用 AgentId。 */
    private int agentId = 0;
    /** 应用 Secret。 */
    private String secret = "";
    /** 接收消息回调的 Token。 */
    private String token = "";
    /** 接收消息回调的 EncodingAESKey。 */
    private String encodingAesKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public int getAgentId() {
        return agentId;
    }

    public void setAgentId(int agentId) {
        this.agentId = agentId;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEncodingAesKey() {
        return encodingAesKey;
    }

    public void setEncodingAesKey(String encodingAesKey) {
        this.encodingAesKey = encodingAesKey;
    }
}
