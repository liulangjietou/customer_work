package com.richard.fyoung.customerweb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书 Channel 配置（{@code customer-web.channel.feishu.*}）。
 *
 * <p>飞书走"应用 + 事件回调"模型：inbound 由飞书把事件 POST 到
 * {@code /api/channels/feishu/{channelId}/callback}；outbound 经飞书 API 回复。需在飞书开放平台创建应用，
 * 配置事件订阅指向上面的公网回调地址，并填入 {@code appId/appSecret}（加密回调再配
 * {@code encryptKey/verificationToken}）。默认关闭。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "customer-web.channel.feishu")
public class FeishuProperties {

    /** 是否启用飞书 Channel。默认关闭。 */
    private boolean enabled = false;
    /** 通道 ID（回调路径 /api/channels/feishu/{channelId}/callback 中的 channelId）。 */
    private String channelId = "feishu";
    /** 飞书应用 App ID。 */
    private String appId = "";
    /** 飞书应用 App Secret。 */
    private String appSecret = "";
    /** 事件加密 Encrypt Key（开启加密回调时配置，否则留空）。 */
    private String encryptKey = "";
    /** 事件订阅 Verification Token（用于校验回调来源）。 */
    private String verificationToken = "";

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

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }
}
