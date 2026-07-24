package com.richard.fyoung.customeradmin.aiconfig.channelrobot;

/**
 * 渠道机器人会话模式。
 *
 * <p>continuous：同一外部用户复用同一 sessionId，多轮携带上下文（ai_channel_session 映射）；
 * per_message：每条消息独立会话，不携带历史上下文（customer-channel 侧本地生成一次性 sessionId）。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SessionMode {

    /** 持续会话（默认）。 */
    CONTINUOUS("continuous"),
    /** 单次问答。 */
    PER_MESSAGE("per_message");

    private final String code;

    SessionMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 按 code 解析，未匹配返回 null（由调用方决定 fast fail 还是取默认值）。 */
    public static SessionMode fromCode(String code) {
        for (SessionMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return null;
    }
}
