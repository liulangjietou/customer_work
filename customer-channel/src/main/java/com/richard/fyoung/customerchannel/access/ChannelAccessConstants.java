package com.richard.fyoung.customerchannel.access;

/**
 * 渠道接入层常量（避免魔法值）。
 *
 * <p>集中定义：开放 API 头/路径、指令关键字、回复话术、SSE 事件名、错误码。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ChannelAccessConstants {

    private ChannelAccessConstants() {
    }

    /** 开放 API 鉴权头。 */
    public static final String OPEN_API_TOKEN_HEADER = "X-Open-Api-Token";

    /** 渠道类型：钉钉。 */
    public static final String CHANNEL_TYPE_DINGTALK = "dingtalk";

    // ===== 开放 API 路径 =====
    public static final String PATH_ROBOTS = "/api/open/channel/robots";
    public static final String PATH_SESSION_RESOLVE = "/api/open/channel/sessions/resolve";
    public static final String PATH_SESSION_RESET = "/api/open/channel/sessions/reset";
    public static final String PATH_AGENT_CHAT = "/api/open/agents/{agentCode}/chat";

    // ===== 会话模式（与 admin 侧 ai_channel_robot.session_mode 取值一致）=====
    /** 持续会话：同一外部用户复用同一 sessionId，多轮携带上下文。 */
    public static final String SESSION_MODE_CONTINUOUS = "continuous";
    /** 单次问答：每条消息独立会话，不携带历史上下文。 */
    public static final String SESSION_MODE_PER_MESSAGE = "per_message";
    /** 单次问答模式的一次性 sessionId 前缀（本地生成，不经 admin 会话映射）。 */
    public static final String ONESHOT_SESSION_PREFIX = "ch-oneshot-";

    // ===== 指令关键字（文本 trim 后精确匹配则开启新会话）=====
    public static final String CMD_NEW_SLASH = "/new";
    public static final String CMD_NEW_CN = "新会话";

    // ===== 回复话术 =====
    public static final String HINT_NEW_SESSION = "已开启新会话，可以开始提问了";
    public static final String HINT_PER_MESSAGE_NO_RESET = "当前为单次问答模式，每条消息都是全新上下文，无需重置";
    public static final String HINT_NON_TEXT = "目前只支持文本消息";
    public static final String HINT_ERROR = "抱歉，服务暂时不可用，请稍后再试";

    // ===== SSE 事件名 =====
    public static final String SSE_EVENT_MESSAGE = "message";
    public static final String SSE_EVENT_DONE = "done";
    public static final String SSE_EVENT_ERROR = "error";
    public static final String SSE_DONE_MARKER = "[DONE]";

    // ===== 错误码（error 日志占位符）=====
    public static final String CODE_ROBOTS_FETCH_FAIL = "CHANNEL-ACCESS-ROBOTS-FETCH-FAIL";
    public static final String CODE_REFRESH_FAIL = "CHANNEL-ACCESS-REFRESH-FAIL";
    public static final String CODE_START_FAIL = "CHANNEL-ACCESS-START-FAIL";
    public static final String CODE_STOP_FAIL = "CHANNEL-ACCESS-STOP-FAIL";
    public static final String CODE_CHAT_FAIL = "CHANNEL-ACCESS-CHAT-FAIL";
    public static final String CODE_REPLY_FAIL = "CHANNEL-ACCESS-REPLY-FAIL";
    public static final String CODE_HANDLE_FAIL = "CHANNEL-ACCESS-HANDLE-FAIL";
}
