package com.richard.fyoung.customerwork.core.constant;

/**
 * 开放 API 的协议字面量（鉴权头 + 流式事件名）。
 *
 * <p>服务端在后台（{@code OpenAgentChatController}）、客户端在渠道模块（{@code AdminOpenApiClient}），
 * 两个模块互不依赖、只共同依赖 starter，故协议常量落在这里。此前两侧各写一份：
 * 事件名改一边，另一边收到的流会被当成未知事件默默丢弃，不抛异常也不打日志。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class OpenApiProtocol {

    /** 开放 API 令牌请求头。 */
    public static final String TOKEN_HEADER = "X-Open-Api-Token";
    /** 运行时配置 ACK 专用的实例级令牌请求头；不得复用租户通用 Open API 凭据。 */
    public static final String RUNTIME_CONFIG_ACK_TOKEN_HEADER = "X-Runtime-Config-Ack-Token";

    /** SSE 事件名：一个回复增量。 */
    public static final String SSE_EVENT_MESSAGE = "message";
    /** SSE 事件名：流正常结束。 */
    public static final String SSE_EVENT_DONE = "done";
    /** SSE 事件名：流以错误结束。 */
    public static final String SSE_EVENT_ERROR = "error";
    /** 结束事件的负载标记（沿用 OpenAI 兼容协议的写法，便于既有客户端直接对接）。 */
    public static final String SSE_DONE_MARKER = "[DONE]";

    private OpenApiProtocol() {
    }
}
