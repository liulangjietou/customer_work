package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 生成 commit message 请求体。
 *
 * @param sessionId 会话 ID，用于定位 {@code sessions/{sessionId}/} 目录
 * @param style     文案风格：{@code conventional}（Conventional Commits，默认）｜{@code simple}（简单一句话）
 * @author owlzhangfq@gmail.com
 */
public record CommitMessageRequest(
        @NotBlank(message = "sessionId 不能为空") String sessionId,
        @Pattern(regexp = "conventional|simple", message = "style 仅支持 conventional 或 simple") String style) {

    public String styleOrDefault() {
        return style == null || style.isBlank() ? "conventional" : style;
    }
}
