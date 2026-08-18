package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 自动化重构任务：先发计划确认事件，批准后才进入文件变更链路。 */
public record RefactorTask(
        @NotBlank(message = "sessionId不能为空") String sessionId,
        @NotNull(message = "taskType不能为空") TaskType taskType,
        @NotBlank(message = "description不能为空")
        @Size(max = 10000, message = "description长度不能超过10000字符") String description,
        @Size(max = 200, message = "targetFiles不能超过200个") List<@NotBlank String> targetFiles) {

    public enum TaskType {
        REPLACE,
        API_MIGRATION,
        DEPENDENCY_UPGRADE,
        STYLE
    }

    public List<String> safeTargetFiles() {
        return targetFiles == null ? List.of() : List.copyOf(targetFiles);
    }
}
