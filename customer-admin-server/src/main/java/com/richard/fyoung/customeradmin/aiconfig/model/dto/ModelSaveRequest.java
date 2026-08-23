package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 模型新建/编辑请求。新建时 {@code apiKey} 必填；编辑且 baseUrl 不变时可不传（留空=不改 AppKey，
 * 避免每次编辑都要求重新输入明文密钥）。已有凭据的模型修改 baseUrl 时必须同时重新提交凭据，防止把
 * 旧 SecretRef 重定向到另一个端点。不暴露 temperature/maxTokens——这两项交给模型自身默认值，
 * 不做二次调参（{@link com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory#buildModel}
 * 本就没用过这两个字段）。
 * @author owlzhangfq@gmail.com
 */
public record ModelSaveRequest(
    Long assetId,
    String assetCode,
    String assetName,
    String vendor,
    String family,
    String assetVersion,
    String modality,
    Integer contextWindow,
    Integer maxOutputTokens,
    Boolean supportsStream,
    Boolean supportsTool,
    Boolean supportsJsonSchema,
    Boolean supportsMultimodal,
    @NotBlank(message = "modelName 不能为空") String modelName,
    String deploymentCode,
    String provider,
    String apiKey,
    LocalDateTime secretExpiresAt,
    @NotBlank(message = "baseUrl 不能为空") String baseUrl,
    String region,
    String environment,
    @NotBlank(message = "model 不能为空") String model,
    Boolean isDefault,
    Integer status,
    String lifecycleStatus) {

    /** 兼容旧调用方和存量单测的七参数构造。 */
    public ModelSaveRequest(String modelName, String provider, String apiKey, String baseUrl,
                            String model, Boolean isDefault, Integer status) {
        this(null, null, null, null, null, null, null, null, null,
            null, null, null, null, modelName, null, provider, apiKey, null,
            baseUrl, null, null, model, isDefault, status, null);
    }
}
