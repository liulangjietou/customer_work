package com.richard.fyoung.customeradmin.ticket.dto;

/** 工单回复回执；message 为空表示查询成功但未找到本人这次发送的保存记录。 */
public record TicketMessageReceiptVO(String clientMsgId, TicketMessageVO message) { }
