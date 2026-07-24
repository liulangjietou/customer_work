package com.richard.fyoung.customerchannel.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 渠道接入层配置（{@code customer-channel.access.*}）。
 *
 * <p>本层与旧的 {@code DingTalkChannelConfigurer}（绑本地 Mock agent 的演示）彼此独立、默认关闭。
 * 启用后定时从 admin 开放 API 拉机器人列表并维护到各 IM 平台的连接。</p>
 * @author owlzhangfq@gmail.com
 */
@ConfigurationProperties(prefix = "customer-channel.access")
public class ChannelAccessProperties {

    /** 是否启用渠道接入层。默认关闭。 */
    private boolean enabled = false;
    /** admin 开放 API 基地址。 */
    private String adminBaseUrl = "http://localhost:8082";
    /** admin 开放 API 令牌（建议用环境变量 CHANNEL_ACCESS_ADMIN_TOKEN 注入）。 */
    private String adminToken = "";
    /** 机器人列表刷新间隔（秒）。 */
    private int refreshIntervalSeconds = 30;
    /** 单次对话聚合超时（秒），超时回复友好错误。 */
    private int chatTimeoutSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminBaseUrl() {
        return adminBaseUrl;
    }

    public void setAdminBaseUrl(String adminBaseUrl) {
        this.adminBaseUrl = adminBaseUrl;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public int getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(int refreshIntervalSeconds) {
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }

    public int getChatTimeoutSeconds() {
        return chatTimeoutSeconds;
    }

    public void setChatTimeoutSeconds(int chatTimeoutSeconds) {
        this.chatTimeoutSeconds = chatTimeoutSeconds;
    }
}
