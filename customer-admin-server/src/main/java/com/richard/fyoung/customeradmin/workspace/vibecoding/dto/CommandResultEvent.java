package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/** {@code command_result} SSE 事件：命令唯一终态。 */
public record CommandResultEvent(
        int exitCode,
        boolean success,
        long durationMs,
        boolean timedOut,
        String containerId) {
}
