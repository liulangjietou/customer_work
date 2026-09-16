package com.richard.fyoung.customerwork.infra.ws;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;

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
    /** 输入已落库；重复投递返回同一条消息，不表示 AI 或业务工具已经完成。 */
    public static final String TYPE_CHAT_ACCEPTED = "chat_accepted";
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
    public static final String KEY_ID = "id";
    public static final String KEY_ACCEPTANCE = "acceptance";
    public static final String ACCEPTANCE_REJECTED = "REJECTED";
    public static final String ACCEPTANCE_UNKNOWN = "UNKNOWN";
    public static final String ACCEPTANCE_ACCEPTED = "ACCEPTED";

    /**
     * 客户端为每条消息生成的标识，重发时沿用同一个值。
     *
     * <p>服务端据此去重：WebSocket 在移动网络下断连是常态、客户端重发是必须的，
     * 而同一句话到两次就是两次完整的对话轮——双份 token，还可能提交两张退款工单。
     * 不带这个字段的老客户端一律按首次处理（去重是增益，不是安全边界）。</p>
     */
    public static final String KEY_CLIENT_MSG_ID = "clientMsgId";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_FINISH_REASON = "finishReason";
    public static final String KEY_USAGE = "usage";
    public static final String KEY_TRACE_ID = "traceId";

    /** 对话转发帧（data 结构与前端契约对齐：messageId/sessionId/ticketId/senderType/senderId/content/ts）。 */
    public static WsFrame chat(Object data) {
        return new WsFrame(TYPE_CHAT, data);
    }

    /** 客户和坐席转发共用持久化消息投影，游标必须来自消息表。 */
    public static WsFrame chatMessage(ChatMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_ID, message.id());
        data.put(KEY_MESSAGE_ID, message.messageId());
        data.put(KEY_SESSION_ID, message.sessionId());
        data.put(KEY_TICKET_ID, message.ticketId());
        data.put(KEY_SENDER_TYPE, message.senderType().name());
        data.put(KEY_SENDER_ID, message.senderId());
        data.put(KEY_CONTENT, message.content());
        data.put(KEY_TS, message.createdAtMs());
        return chat(data);
    }

    /** 受理回执只使用存储返回的消息号和游标，前端据此替换临时气泡并核对重发。 */
    public static WsFrame chatAccepted(String clientMsgId, ChatMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        data.put(KEY_ID, message.id());
        data.put(KEY_MESSAGE_ID, message.messageId());
        data.put(KEY_SESSION_ID, message.sessionId());
        data.put(KEY_TICKET_ID, message.ticketId());
        data.put(KEY_TS, message.createdAtMs());
        return new WsFrame(TYPE_CHAT_ACCEPTED, data);
    }

    /** AI 流式增量帧：{@code {content}}。 */
    public static WsFrame chatChunk(String content) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CONTENT, content);
        return new WsFrame(TYPE_CHAT_CHUNK, data);
    }

    /** 增量附带服务端确定的会话与请求归属，避免同一用户多个页面串流。 */
    public static WsFrame chatChunk(String content, String sessionId, String ticketId, String clientMsgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CONTENT, content);
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_TICKET_ID, ticketId);
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        return new WsFrame(TYPE_CHAT_CHUNK, data);
    }

    /** 已保存回复携带真实分页游标和原输入标识，旧客户端仍可按原有字段消费。 */
    public static WsFrame chatDone(ChatTerminalEnvelope terminal, ChatMessage message, String clientMsgId) {
        Map<String, Object> data = terminalData(terminal, message.sessionId(), message.ticketId(), message.content(), message.createdAtMs());
        data.put(KEY_ID, message.id());
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        return new WsFrame(TYPE_CHAT_DONE, data);
    }

    /** AI 流式结束帧：终止信封 + 会话归属 + 全文，前端据此定稿流式气泡。 */
    public static WsFrame chatDone(ChatTerminalEnvelope terminal, String sessionId, String ticketId,
                                   String content, long ts) {
        return new WsFrame(TYPE_CHAT_DONE, terminalData(terminal, sessionId, ticketId, content, ts));
    }

    private static Map<String, Object> terminalData(ChatTerminalEnvelope terminal, String sessionId,
                                                     String ticketId, String content, long ts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_MESSAGE_ID, terminal.messageId());
        data.put(KEY_FINISH_REASON, terminal.finishReason());
        data.put(KEY_USAGE, terminal.usage());
        data.put(KEY_TRACE_ID, terminal.traceId());
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_TICKET_ID, ticketId);
        data.put(KEY_CONTENT, content);
        data.put(KEY_TS, ts);
        return data;
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

    /** 在原错误帧上补充本条消息归属和受理状态，旧客户端仍可读取 code/message。 */
    public static WsFrame messageError(String code, String message, String sessionId,
                                        String clientMsgId, String acceptance) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CODE, code);
        data.put(KEY_MESSAGE, message);
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        data.put(KEY_ACCEPTANCE, acceptance);
        return new WsFrame(TYPE_ERROR, data);
    }

    /** 坐席按工单核对发送，错误帧必须携带对应工单而非拿工单号冒充会话号。 */
    @SuppressWarnings("unchecked")
    public static WsFrame agentMessageError(String code, String message, String ticketId,
                                             String clientMsgId, String acceptance) {
        WsFrame frame = messageError(code, message, null, clientMsgId, acceptance);
        ((Map<String, Object>) frame.data()).put(KEY_TICKET_ID, ticketId);
        return frame;
    }

    /** 心跳响应帧。 */
    public static WsFrame pong() {
        return new WsFrame(TYPE_PONG, null);
    }
}
