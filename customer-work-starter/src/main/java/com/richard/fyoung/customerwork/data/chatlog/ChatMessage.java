package com.richard.fyoung.customerwork.data.chatlog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import java.util.List;

/**
 * 聊天消息（不可变）：会话与工单双维度的对话留痕。
 *
 * <p>{@code id} 为存储层自增主键（游标翻页用），追加前置 0 由存储层回填；{@code messageId} 为业务
 * 幂等标识 {@code MSG-<uuid>}。{@code senderType} 复用 {@link TicketActorType}（USER/BOT/AGENT/SYSTEM）
 * 标识发送方；{@code ticketId} 可空（未转人工的纯 AI 对话消息不关联工单）。</p>
 *
 * @param id          存储层自增主键（追加前为 0）
 * @param messageId   业务消息号 MSG-&lt;uuid&gt;
 * @param sessionId   所属会话
 * @param ticketId    关联工单号（可空）
 * @param senderType  发送方类型
 * @param senderId    发送方标识（可空）
 * @param content     消息内容
 * @param createdAtMs 创建时间戳（毫秒）
 * @param answerEvidence 内部保存的答复信息，对外只投影可见字段，旧消息为 null
 * @author owlzhangfq@gmail.com
 */
public record ChatMessage(long id, String messageId, String sessionId, String ticketId,
                          TicketActorType senderType, String senderId, String content, long createdAtMs,
                          @JsonIgnore ChatAnswerEvidence answerEvidence) {

    /** 旧消息和人工消息没有答复采集信息，不根据正文补造终态。 */
    public ChatMessage(long id, String messageId, String sessionId, String ticketId,
                       TicketActorType senderType, String senderId, String content, long createdAtMs) {
        this(id, messageId, sessionId, ticketId, senderType, senderId, content, createdAtMs, null);
    }

    /** 新建消息工厂：id 交由存储层回填（此处置 0），时间戳取当前。 */
    public static ChatMessage of(String messageId, String sessionId, String ticketId,
                                 TicketActorType senderType, String senderId, String content) {
        return new ChatMessage(0L, messageId, sessionId, ticketId, senderType, senderId, content,
            System.currentTimeMillis());
    }

    /** 回填自增主键后的副本。 */
    public ChatMessage withId(long assignedId) {
        return new ChatMessage(assignedId, messageId, sessionId, ticketId, senderType, senderId,
            content, createdAtMs, answerEvidence);
    }

    /** 答复信息先与正文组成不可变消息，再交给存储层一次写入。 */
    public ChatMessage withAnswerEvidence(ChatAnswerEvidence evidence) {
        return new ChatMessage(id, messageId, sessionId, ticketId, senderType, senderId, content, createdAtMs, evidence);
    }

    /** 历史接口仅投影客户可见的来源线索，内部版本引用不直接序列化出站。 */
    @JsonProperty("citations")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<KnowledgeCitation> citations() {
        return answerEvidence == null ? null : answerEvidence.citations();
    }

    @JsonProperty("taskPlan")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<TaskPlanItem> taskPlan() {
        return answerEvidence == null ? null : answerEvidence.taskPlan();
    }

    @JsonProperty("finishReason")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String finishReason() {
        return answerEvidence == null ? null : answerEvidence.finishReason();
    }
}
