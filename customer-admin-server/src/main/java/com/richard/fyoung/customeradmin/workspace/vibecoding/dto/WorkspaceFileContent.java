package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * 工作区文件内容（供前端代码高亮预览）。
 *
 * @param relativePath 相对于会话 workspace 根目录的路径
 * @param language     编程语言标识（java/xml/yaml/json/typescript 等），供高亮组件使用
 * @param content      文件内容；二进制文件或超大文件时为提示文本
 * @param truncated    内容是否被截断/不可预览（超大文件/二进制文件时为 {@code true}）
 * @author owlzhangfq@gmail.com
 */
public record WorkspaceFileContent(String relativePath, String language, String content, boolean truncated) {
}
