package com.richard.fyoung.customeradmin.workbench.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内网工作台令牌视图对象（列表展示用）。只回显前缀与元信息，绝不含明文/哈希。
 * @author owlzhangfq@gmail.com
 */
@Data
public class WorkbenchTokenVO {
    private Long id;
    private String name;
    /** 令牌前缀（如 wbt_ab12cd34），供用户定位是哪一个。 */
    private String tokenPrefix;
    private LocalDateTime expireTime;
    private LocalDateTime lastUsedTime;
    private Boolean revoked;
    private LocalDateTime createTime;
}
