package com.richard.fyoung.customerchannel.access.model;

/**
 * 归一化的渠道入站消息（各平台连接器把原始消息解析成此结构，交给统一管道处理）。
 *
 * <p>平台差异（如钉钉单聊/群聊的 externalUserId 计算、非文本判定）在连接器里消化，
 * 管道只依赖此结构，从而与具体渠道解耦——加企微/微信只需新增连接器。</p>
 * @author owlzhangfq@gmail.com
 */
public class ChannelInboundMessage {

    /** 渠道类型（dingtalk/wecom/...）。 */
    private final String channelType;
    /** IM 应用 appKey，用于向 admin resolve/reset 会话。 */
    private final String appKey;
    /** 目标智能体编码。 */
    private final String agentCode;
    /** 会话模式：continuous 持续会话 / per_message 单次问答（来自机器人配置，空值按持续会话处理）。 */
    private final String sessionMode;
    /** 外部用户标识：单聊=用户 id；群聊=群 id#用户 id（按群+人隔离会话）。 */
    private final String externalUserId;
    /** 是否文本消息。 */
    private final boolean text;
    /** 文本内容（非文本消息时为 null/空）。 */
    private final String content;

    public ChannelInboundMessage(String channelType, String appKey, String agentCode, String sessionMode,
                                 String externalUserId, boolean text, String content) {
        this.channelType = channelType;
        this.appKey = appKey;
        this.agentCode = agentCode;
        this.sessionMode = sessionMode;
        this.externalUserId = externalUserId;
        this.text = text;
        this.content = content;
    }

    public String getChannelType() {
        return channelType;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAgentCode() {
        return agentCode;
    }

    public String getSessionMode() {
        return sessionMode;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public boolean isText() {
        return text;
    }

    public String getContent() {
        return content;
    }

    /** 串行处理键：同一 (渠道+应用+用户) 的消息串行，等价于 sessionId 串行。 */
    public String serialKey() {
        return channelType + "|" + appKey + "|" + externalUserId;
    }
}
