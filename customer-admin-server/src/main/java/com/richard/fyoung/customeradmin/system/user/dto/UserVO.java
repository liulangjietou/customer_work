package com.richard.fyoung.customeradmin.system.user.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户视图对象（不含密码）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class UserVO {
    private Long id;
    private String username;
    /** 用户当前归属租户。 */
    private String tenantId;
    private String nickname;
    /** 注册邮箱；自助注册账号才有，审核通过时可直接作为新租户的联系邮箱。 */
    private String email;
    private Integer status;
    private String approvalStatus;
    private Long approvalBy;
    private LocalDateTime approvalTime;
    private String approvalRemark;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private LocalDateTime createTime;
    private List<Long> roleIds;
    private List<String> roleNames;
}
