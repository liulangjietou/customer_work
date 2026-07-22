package com.richard.fyoung.customeradmin.workbench.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建内网工作台令牌请求。{@code expireDays} 为 null 表示永不过期。
 * @author owlzhangfq@gmail.com
 */
public record WorkbenchTokenCreateRequest(
    @NotBlank(message = "name 不能为空") String name,
    Integer expireDays) {
}
