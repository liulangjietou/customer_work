package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerwork.core.constant.ChannelTypes;
import com.richard.fyoung.customerwork.core.constant.OpenApiProtocol;

/**
 * 渠道接入层常量（避免魔法值）。
 *
 * <p>集中定义：开放 API 路径、指令关键字、回复话术、错误码。</p>
 *
 * <p>鉴权头与 SSE 事件名<b>不在这里</b>：那是与后台服务端共用的协议，定义在 starter 的
 * {@link OpenApiProtocol}，两侧引用同一处，避免改了服务端忘了客户端。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ChannelAccessConstants {

    private ChannelAccessConstants() {
    }

    /** 渠道类型：钉钉（编码与后台 {@code ChannelType} 共用 {@link ChannelTypes} 一处定义）。 */
    public static final String CHANNEL_TYPE_DINGTALK = ChannelTypes.DINGTALK;
    /** 渠道类型：微信公众号。 */
    public static final String CHANNEL_TYPE_WECHAT = ChannelTypes.WECHAT;

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

    // ===== 微信公众号（入站回调 + 客服消息主动推送）=====
    /** 微信 API 基地址。 */
    public static final String WECHAT_API_BASE_URL = "https://api.weixin.qq.com";
    /** 换取 access_token 路径（GET，grant_type=client_credential）。 */
    public static final String WECHAT_PATH_ACCESS_TOKEN = "/cgi-bin/token";
    /** 客服消息发送路径（POST，access_token 拼到 query）。 */
    public static final String WECHAT_PATH_CUSTOM_SEND = "/cgi-bin/message/custom/send";
    /** 回调必须在 5 秒内应答的固定成功串（返回它微信才认为投递成功、不再重试）。 */
    public static final String WECHAT_CALLBACK_SUCCESS = "success";
    /** 客服消息单条文本长度上限（保守取 1000 字符，超长分段多条发送）。 */
    public static final int WECHAT_SEGMENT_MAX_CHARS = 1000;
    /** access_token 提前刷新的时间窗（秒）：过期前 5 分钟即视为需要刷新。 */
    public static final int WECHAT_TOKEN_REFRESH_AHEAD_SECONDS = 300;
    /** 业务错误码：access_token 无效（触发强刷一次重试）。 */
    public static final int WECHAT_ERRCODE_INVALID_TOKEN = 40001;
    /** 业务错误码：access_token 过期（触发强刷一次重试）。 */
    public static final int WECHAT_ERRCODE_EXPIRED_TOKEN = 42001;
    /** MsgId 去重环形容量（有界 LRU，防微信重试重复触发管道）。 */
    public static final int WECHAT_MSGID_DEDUP_CAPACITY = 4096;
    /** 微信文本消息类型。 */
    public static final String WECHAT_MSG_TYPE_TEXT = "text";

    // ===== 错误码（error 日志占位符）=====
    public static final String CODE_ROBOTS_FETCH_FAIL = "CHANNEL-ACCESS-ROBOTS-FETCH-FAIL";
    public static final String CODE_REFRESH_FAIL = "CHANNEL-ACCESS-REFRESH-FAIL";
    public static final String CODE_START_FAIL = "CHANNEL-ACCESS-START-FAIL";
    public static final String CODE_STOP_FAIL = "CHANNEL-ACCESS-STOP-FAIL";
    public static final String CODE_CHAT_FAIL = "CHANNEL-ACCESS-CHAT-FAIL";
    public static final String CODE_REPLY_FAIL = "CHANNEL-ACCESS-REPLY-FAIL";
    public static final String CODE_HANDLE_FAIL = "CHANNEL-ACCESS-HANDLE-FAIL";
    public static final String CODE_WECHAT_TOKEN_FAIL = "CHANNEL-ACCESS-WECHAT-TOKEN-FAIL";
    public static final String CODE_WECHAT_SEND_FAIL = "CHANNEL-ACCESS-WECHAT-SEND-FAIL";
    public static final String CODE_WECHAT_CALLBACK_FAIL = "CHANNEL-ACCESS-WECHAT-CALLBACK-FAIL";
    public static final String CODE_WECHAT_UNKNOWN_APPID = "CHANNEL-ACCESS-WECHAT-UNKNOWN-APPID";
}
