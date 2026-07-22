package com.richard.fyoung.customeradmin.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 内网工作台站点新建/编辑请求。编辑时 {@code password} 留空=不改密码
 * （同 {@code SqlDatasourceSaveRequest} 手法，避免每次编辑都要重填明文）。
 *
 * <p>后半段为自动登录配置，全部可空——留空时脚本用内置启发式定位元素、时序走默认值。</p>
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
    Boolean enabled,

    @Size(max = 255, message = "usernameSelector 过长") String usernameSelector,
    @Size(max = 255, message = "passwordSelector 过长") String passwordSelector,
    @Size(max = 255, message = "submitSelector 过长") String submitSelector,
    String fillMode,
    String submitMode,
    Integer initDelayMs,
    Integer submitDelayMs) {
}
