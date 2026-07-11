package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 保存文件内容请求体。
 *
 * @param sessionId    会话 ID，用于定位 {@code sessions/{sessionId}/} 目录
 * @param relativePath 相对于会话 workspace 的文件路径（如 {@code src/main/java/Foo.java}）
 * @param content      文件的完整新内容
 * @author owlzhangfq@gmail.com
 */
public record SaveFileContentRequest(
        @NotBlank String sessionId,
        @NotBlank String relativePath,
        @NotNull String content) {
}
