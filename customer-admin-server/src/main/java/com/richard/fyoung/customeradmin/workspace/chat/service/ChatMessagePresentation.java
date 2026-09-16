package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工作区用户原文的读写契约，与模型所需的运行指引分开保存。 */
final class ChatMessagePresentation {

    private static final String RAW_INPUT_KEY = "workspace.rawInput";
    private static final String SESSION_TYPE_KEY = "workspace.sessionType";

    private ChatMessagePresentation() {
    }

    /** 调用方已捕获原始问题；这里只将该事实写进消息元数据，不反向解析富文本。 */
    static Map<String, Object> metadata(AgentCallMeta callMeta) {
        if (callMeta == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (callMeta.question() != null) {
            metadata.put(RAW_INPUT_KEY, callMeta.question());
        }
        if (callMeta.sessionType() != null) {
            metadata.put(SESSION_TYPE_KEY, callMeta.sessionType().name());
        }
        return metadata;
    }

    /** 仅显式标记过的用户消息采用原文；旧消息与助手消息完整保留。 */
    static String text(Msg message) {
        Object original = message.getMetadata().get(RAW_INPUT_KEY);
        return message.getRole() == MsgRole.USER && original instanceof String value
            ? value : message.getTextContent();
    }

    static String sessionType(Msg message) {
        Object value = message.getMetadata().get(SESSION_TYPE_KEY);
        return value instanceof String type ? type : null;
    }
}
