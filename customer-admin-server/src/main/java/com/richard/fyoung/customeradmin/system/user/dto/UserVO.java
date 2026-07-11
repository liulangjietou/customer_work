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
    private String nickname;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;
    private LocalDateTime createTime;
    private List<Long> roleIds;
    private List<String> roleNames;
}
