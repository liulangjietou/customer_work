package com.richard.fyoung.customeradmin.system.user.dto;

import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 自助注册用户审核请求；批准时角色清单必填，拒绝时服务端会清空角色。
 * @author owlzhangfq@gmail.com
 */
public record UserApprovalRequest(
    @NotNull(message = "decision 不能为空") UserApprovalStatus decision,
    List<Long> roleIds,
    @Size(max = 255, message = "审核说明不能超过 255 位") String remark) {
}
