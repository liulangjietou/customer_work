package com.richard.fyoung.customerwork.infra.ws;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 帧（统一信封）：{@code {"type": "...", "data": ...}}。
 *
 * <p>所有上下行帧都走此结构，前端按 {@code type} 分派。静态工厂集中构帧，避免各处硬拼 type 字符串。
 * {@code data} 用弱类型 {@link Object}（多为 Map / 字符串），交由 Jackson 序列化。仅依赖 JDK + Jackson，
 * 与具体 WebFlux 路由无关，故作为可复用基建下沉 starter，接入方按业务绑定具体 WebSocketHandler。</p>
 * @author owlzhangfq@gmail.com
 */
public record WsFrame(String type, Object data) {

    /** 用户/坐席一条完整对话消息（转发通道）。 */
    public static final String TYPE_CHAT = "chat";
    /** AI 流式增量片段。 */
    public static final String TYPE_CHAT_CHUNK = "chat_chunk";
    /** AI 流式结束（携带落库后的 messageId）。 */
    public static final String TYPE_CHAT_DONE = "chat_done";
    /** 工单状态流转事件。 */
    public static final String TYPE_TICKET_EVENT = "ticket_event";
    /** 新工单进入排队（广播给坐席抢单）。 */
    public static final String TYPE_TICKET_NEW = "ticket_new";
    /** 系统提示（如"正在转接人工"）。 */
    public static final String TYPE_SYSTEM = "system";
    /** 错误帧。 */
    public static final String TYPE_ERROR = "error";
    /** 心跳请求。 */
    public static final String TYPE_PING = "ping";
    /** 心跳响应。 */
    public static final String TYPE_PONG = "pong";

    // ---- 帧字段名：与 TYPE_* 同为前端契约的一部分，接入方拼 data 时一律引用这里，不要各自写字面量 ----

    /** 信封字段：帧类型。 */
    public static final String KEY_TYPE = "type";
    /** 信封字段：帧负载。 */
    public static final String KEY_DATA = "data";

    public static final String KEY_CONTENT = "content";
    public static final String KEY_MESSAGE_ID = "messageId";
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_TICKET_ID = "ticketId";
    public static final String KEY_SENDER_TYPE = "senderType";
    public static final String KEY_SENDER_ID = "senderId";
    public static final String KEY_TS = "ts";
    public static final String KEY_CODE = "code";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_FINISH_REASON = "finishReason";
    public static final String KEY_USAGE = "usage";
    public static final String KEY_TRACE_ID = "traceId";

    /** 对话转发帧（data 结构与前端契约对齐：messageId/sessionId/ticketId/senderType/senderId/content/ts）。 */
    public static WsFrame chat(Object data) {
        return new WsFrame(TYPE_CHAT, data);
    }

    /** AI 流式增量帧：{@code {content}}。 */
    public static WsFrame chatChunk(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CONTENT, content);
        return new WsFrame(TYPE_CHAT_CHUNK, data);
    }

    /** AI 流式结束帧：终止信封 + 会话归属 + 全文，前端据此定稿流式气泡。 */
    public static WsFrame chatDone(ChatTerminalEnvelope terminal, String sessionId, String ticketId,
                                   String content, long ts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_MESSAGE_ID, terminal.messageId());
        data.put(KEY_FINISH_REASON, terminal.finishReason());
        data.put(KEY_USAGE, terminal.usage());
        data.put(KEY_TRACE_ID, terminal.traceId());
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_TICKET_ID, ticketId);
        data.put(KEY_CONTENT, content);
        data.put(KEY_TS, ts);
        return new WsFrame(TYPE_CHAT_DONE, data);
    }

    /** 工单事件帧。 */
    public static WsFrame ticketEvent(Object data) {
        return new WsFrame(TYPE_TICKET_EVENT, data);
    }

    /** 新工单广播帧。 */
    public static WsFrame ticketNew(Object data) {
        return new WsFrame(TYPE_TICKET_NEW, data);
    }

    /** 系统提示帧（无会话上下文的旧格式）：{@code {content, ts}}。有会话上下文时优先用三参重载。 */
    public static WsFrame system(String content) {
        return system(content, null, null);
    }

    /**
     * 系统提示帧（带会话归属）：{@code {content, sessionId?, ticketId?, ts}}。
     *
     * <p>sessionId/ticketId 供前端按当前查看会话过滤，避免跨会话误标；为空时不输出该键，
     * 与旧格式帧完全一致（旧客户端对多余字段也天然宽容，双向兼容）。</p>
     */
    public static WsFrame system(String content, String sessionId, String ticketId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CONTENT, content);
        if (sessionId != null) {
            data.put(KEY_SESSION_ID, sessionId);
        }
        if (ticketId != null) {
            data.put(KEY_TICKET_ID, ticketId);
        }
        data.put(KEY_TS, System.currentTimeMillis());
        return new WsFrame(TYPE_SYSTEM, data);
    }

    /** 错误帧：{@code {code, message}}（code 用于排障定位，message 面向用户展示）。 */
    public static WsFrame error(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CODE, code);
        data.put(KEY_MESSAGE, message);
        return new WsFrame(TYPE_ERROR, data);
    }

    /** 心跳响应帧。 */
    public static WsFrame pong() {
        return new WsFrame(TYPE_PONG, null);
    }
}
