package com.richard.fyoung.customerwork.capability.assist;

import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import java.util.List;

/** 摘要依据由服务端生成；原文内容只证明对话中出现过，不代表业务结果已经核实。 */
public record SummaryEvidence(String version, long generatedAtMs, int historyLimit,
                              boolean truncated, List<Source> sources) {

    public SummaryEvidence {
        sources = List.copyOf(sources);
    }

    /** 本次实际用于整理的消息摘录；截断时保留消息末尾并显式标记。 */
    public record Source(long id, String messageId, TicketActorType senderType,
                         String excerpt, long createdAtMs, boolean truncated) {
    }
}
