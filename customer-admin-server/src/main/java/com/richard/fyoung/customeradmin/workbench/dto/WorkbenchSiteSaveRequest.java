package com.richard.fyoung.customeradmin.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 内网工作台站点新建/编辑请求。编辑时 {@code password} 留空=不改密码
 * （同 {@code SqlDatasourceSaveRequest} 手法，避免每次编辑都要重填明文）。
 * @author owlzhangfq@gmail.com
 */
public record WorkbenchSiteSaveRequest(
    @NotBlank(message = "name 不能为空") String name,
    String category,
    @NotBlank(message = "url 不能为空")
    @Pattern(regexp = "^https?://.+", message = "url 必须以 http:// 或 https:// 开头") String url,
    String account,
    String password,
    String remark,
    Boolean enabled) {
}
