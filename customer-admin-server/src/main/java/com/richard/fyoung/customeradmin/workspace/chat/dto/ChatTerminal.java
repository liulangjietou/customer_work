package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 本轮权威结果。historySaved 只证明本轮用户消息与主 Agent 结果已回读，
 * 不代表长期记忆、工具业务写入或编码产物已经保存；产物单独由 artifactsSaved 表示。
 */
public record ChatTerminal(String turnId, String messageId, ChatMessagePhase phase,
                           String finishReason, boolean historySaved, Boolean artifactsSaved, String error) {

    /** 没有完成依据时如实保留未知，不能根据 EOF 或兜底文本补成成功。 */
    public static ChatTerminal unknown(String turnId) {
        return new ChatTerminal(turnId, null, ChatMessagePhase.UNKNOWN, null, false, null,
            "完成状态尚未确认，请先查看会话历史再决定是否重新发送。");
    }

    /** 运行链路明确失败，保留可理解的提示，不传出底层异常信息。 */
    public static ChatTerminal failed(String turnId, String reason, String error) {
        return new ChatTerminal(turnId, null, ChatMessagePhase.FAILED, reason, false, null, error);
    }

    /** 编码层在产物保存之后补充结果，保存失败不能呈现为完整成功。 */
    public ChatTerminal withArtifactsSaved(boolean saved) {
        String persistenceError = "产物保存尚未确认。已保留本地内容，请先核对文件与会话历史。";
        return new ChatTerminal(turnId, messageId,
            !saved && phase != ChatMessagePhase.FAILED ? ChatMessagePhase.UNKNOWN : phase,
            finishReason, historySaved, saved,
            saved ? error : error == null ? persistenceError : error + " " + persistenceError);
    }
}
