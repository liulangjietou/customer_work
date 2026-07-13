package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * 当前 VibeCoding 沙箱模式（{@code admin.sandbox.mode} 全局配置，不随会话变化）。
 *
 * @param mode {@code local}｜{@code docker}
 * @author owlzhangfq@gmail.com
 */
public record SandboxModeResponse(String mode) {
}
