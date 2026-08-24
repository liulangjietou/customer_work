package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** PUSH 文档源同步批次。checkpoint 以 CAS 方式推进，requestId 保证重试幂等。 */
public record KnowledgeSyncRequest(
    @NotBlank(message = "requestId 不能为空") @Size(max = 128, message = "requestId 不能超过 128 字符")
    String requestId,
    @Size(max = 512, message = "expectedCheckpoint 不能超过 512 字符") String expectedCheckpoint,
    @NotBlank(message = "checkpoint 不能为空") @Size(max = 512, message = "checkpoint 不能超过 512 字符")
    String checkpoint,
    Boolean fullSnapshot,
    @PositiveOrZero(message = "expectedDocumentCount 不能小于 0") Integer expectedDocumentCount,
    @NotNull(message = "documents 不能为空") List<@Valid KnowledgeDocumentChangeRequest> documents) {
}
