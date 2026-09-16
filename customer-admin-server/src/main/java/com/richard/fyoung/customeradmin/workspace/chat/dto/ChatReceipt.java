package com.richard.fyoung.customeradmin.workspace.chat.dto;

/** 持久受理事实和已记录终态；没有终态只表示待核对，不表示模型仍在运行或已完成。 */
public record ChatReceipt(String clientMessageId, long acceptedAtMs, ChatTerminal terminal) { }
