package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/** {@code command_output} SSE 事件：沙箱命令的一段实时输出。 */
public record CommandOutputEvent(String stream, String text, long timestamp) {
}
