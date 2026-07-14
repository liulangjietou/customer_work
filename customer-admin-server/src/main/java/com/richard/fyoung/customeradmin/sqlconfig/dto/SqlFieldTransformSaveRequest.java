package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 列转换器新建/编辑请求。{@code transformType} 须为 TransformType 枚举之一，
 * VALUE_MAP 时 {@code transformConfig} 须为合法 JSON（Service 校验）。
 * @author owlzhangfq@gmail.com
 */
public record SqlFieldTransformSaveRequest(
    @NotBlank(message = "fieldName 不能为空") String fieldName,
    @NotBlank(message = "transformType 不能为空") String transformType,
    @NotBlank(message = "transformConfig 不能为空") String transformConfig) {
}
