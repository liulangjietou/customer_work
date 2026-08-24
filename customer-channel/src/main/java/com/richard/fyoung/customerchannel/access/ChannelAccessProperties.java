package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerwork.infra.config.properties.DistributedLockProperties;
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
    /** 微信公众号回调安全配置。 */
    private final WeChat wechat = new WeChat();

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

    public WeChat getWechat() {
        return wechat;
    }

    /** 微信回调时间窗与跨实例回放保护。 */
    public static class WeChat {

        /** 回调时间戳允许偏差（秒）。 */
        private int timestampToleranceSeconds = 300;
        /** nonce 占位有效期（秒）。 */
        private int nonceTtlSeconds = 600;
        /** 消息幂等占位有效期（秒）。 */
        private int messageTtlSeconds = 86400;
        /** redis=多实例原子去重；memory=仅单实例测试/开发。 */
        private String replayStore = "redis";
        /** 内存模式最大占位数。 */
        private int memoryMaxEntries = 100000;
        /** Redis key 前缀。 */
        private String keyPrefix = "cw:channel:wechat:replay:";
        /** Redis 连接配置。 */
        private final DistributedLockProperties.Redis redis = new DistributedLockProperties.Redis();

        public int getTimestampToleranceSeconds() {
            return timestampToleranceSeconds;
        }

        public void setTimestampToleranceSeconds(int timestampToleranceSeconds) {
            this.timestampToleranceSeconds = timestampToleranceSeconds;
        }

        public int getNonceTtlSeconds() {
            return nonceTtlSeconds;
        }

        public void setNonceTtlSeconds(int nonceTtlSeconds) {
            this.nonceTtlSeconds = nonceTtlSeconds;
        }

        public int getMessageTtlSeconds() {
            return messageTtlSeconds;
        }

        public void setMessageTtlSeconds(int messageTtlSeconds) {
            this.messageTtlSeconds = messageTtlSeconds;
        }

        public String getReplayStore() {
            return replayStore;
        }

        public void setReplayStore(String replayStore) {
            this.replayStore = replayStore;
        }

        public int getMemoryMaxEntries() {
            return memoryMaxEntries;
        }

        public void setMemoryMaxEntries(int memoryMaxEntries) {
            this.memoryMaxEntries = memoryMaxEntries;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public DistributedLockProperties.Redis getRedis() {
            return redis;
        }
    }
}
