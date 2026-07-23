package com.richard.fyoung.customerchannel.access.model;

/**
 * 渠道机器人（admin 开放 API 返回的机器人配置快照）。
 *
 * <p>一台机器人对应一个 IM 应用凭证 + 一个后台工作区智能体（{@code agentCode}）。
 * {@code version} 取 admin 侧 update_time 毫秒时间戳，用于 diff 判断是否需要 restart。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChannelRobot {

    private final Long id;
    private final String channelType;
    private final String robotName;
    private final String appKey;
    private final String appSecret;
    private final String robotCode;
    private final String agentCode;
    private final Long version;

    public ChannelRobot(Long id, String channelType, String robotName, String appKey,
                        String appSecret, String robotCode, String agentCode, Long version) {
        this.id = id;
        this.channelType = channelType;
        this.robotName = robotName;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.robotCode = robotCode;
        this.agentCode = agentCode;
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getRobotName() {
        return robotName;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public String getRobotCode() {
        return robotCode;
    }

    public String getAgentCode() {
        return agentCode;
    }

    public Long getVersion() {
        return version;
    }
}
