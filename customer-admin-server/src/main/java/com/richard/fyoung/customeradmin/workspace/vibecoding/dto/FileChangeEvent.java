package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

/**
 * VibeCoding 实时文件变更事件（{@code file_change} SSE 事件的 data 载荷）。
 *
 * @param operation   变更类型：{@code CREATE}｜{@code MODIFY}｜{@code DELETE}
 * @param path        相对会话 workspace 的文件路径
 * @param description 变更摘要（一句话说明，当前为按 operation 生成的固定文案，不额外调用模型）
 * @author owlzhangfq@gmail.com
 */
public record FileChangeEvent(String operation, String path, String description) {

    public static final String OPERATION_CREATE = "CREATE";
    public static final String OPERATION_MODIFY = "MODIFY";
    public static final String OPERATION_DELETE = "DELETE";
}
