package com.richard.fyoung.customeradmin.system.loginimage.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录页轮播图管理页回显 VO。
 * @author owlzhangfq@gmail.com
 */
@Data
public class LoginCarouselImageVO {

    private Long id;
    private String imageName;
    private String imageUrl;
    private Integer sortOrder;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
