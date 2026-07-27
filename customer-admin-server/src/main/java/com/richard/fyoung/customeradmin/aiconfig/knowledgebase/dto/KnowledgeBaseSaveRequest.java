package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 知识库新建/编辑请求。新建时 {@code apiKey} 必填；编辑时可不传（留空=不改 AppKey，
 * 避免每次编辑都要求重新输入明文密钥，与 {@code ModelSaveRequest} 同款约定）。
 *
 * @param kbName         知识库名称（唯一）
 * @param baseUrl        RAG 服务基址（不含 /api/v1/knowledge/search 路径）
 * @param appId          知识库应用 ID（请求体 app_id）
 * @param apiKey         AppKey 明文；编辑留空=不改
 * @param contentType    请求 Content-Type，留空按 application/json
 * @param extraHeaders   自定义请求头（JSON 对象字符串），留空=无
 * @param topN           单次检索返回条数，留空按 5
 * @param scoreThreshold 相关度阈值，留空按 0（不过滤）
 * @param status         0禁用 / 1启用，留空按启用
 * @param remark         备注
 * @author owlzhangfq@gmail.com
 */
public record KnowledgeBaseSaveRequest(
    @NotBlank(message = "kbName 不能为空") String kbName,
    @NotBlank(message = "baseUrl 不能为空") String baseUrl,
    @NotBlank(message = "appId 不能为空") String appId,
    String apiKey,
    String contentType,
    String extraHeaders,
    Integer topN,
    BigDecimal scoreThreshold,
    Integer status,
    String remark) {
}
