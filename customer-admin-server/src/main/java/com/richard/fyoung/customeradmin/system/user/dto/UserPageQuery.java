package com.richard.fyoung.customeradmin.system.user.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询，额外支持按注册审核状态筛选。
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserPageQuery extends PageQuery {

    private UserApprovalStatus approvalStatus;
}
