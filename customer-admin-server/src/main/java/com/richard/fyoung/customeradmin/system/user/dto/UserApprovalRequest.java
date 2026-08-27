package com.richard.fyoung.customeradmin.system.user.dto;

import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 自助注册用户审核请求；批准时目标租户与角色清单必填，拒绝时服务端保持原租户并清空角色。
 *
 * <p>{@code newTenant} 给出时走"顺带开一个租户"的路径：先建租户（自动创建并授权
 * 租户管理员角色），再把这个人放进去。此时 {@code tenantId} 与 {@code roleIds} 都忽略——
 * 新租户里的角色是刚刚生成的，审核人不可能提前知道它的 ID。</p>
 * @author owlzhangfq@gmail.com
 */
public record UserApprovalRequest(
    @NotNull(message = "decision 不能为空") UserApprovalStatus decision,
    @Size(max = 64, message = "tenantId 不能超过 64 位") String tenantId,
    List<Long> roleIds,
    @Size(max = 255, message = "审核说明不能超过 255 位") String remark,
    @Valid NewTenant newTenant) {

    /**
     * 审核通过时顺带开通的新租户。
     *
     * @param tenantCode  租户编码，会写进各业务表的 tenant_id，创建后不可改
     * @param tenantName  租户名称，展示用
     * @param contactEmail 联系邮箱，留空时服务端取注册人的邮箱
     */
    public record NewTenant(
        @NotBlank(message = "租户编码不能为空")
        @Size(max = 64, message = "租户编码长度不能超过 64")
        @Pattern(regexp = TenantContext.TENANT_ID_REGEX,
            message = "租户编码只能包含字母、数字、连字符和下划线，且以字母或数字开头")
        String tenantCode,

        @NotBlank(message = "租户名称不能为空")
        @Size(max = 128, message = "租户名称长度不能超过 128")
        String tenantName,

        @Size(max = 128, message = "联系邮箱长度不能超过 128")
        String contactEmail) {
    }
}
