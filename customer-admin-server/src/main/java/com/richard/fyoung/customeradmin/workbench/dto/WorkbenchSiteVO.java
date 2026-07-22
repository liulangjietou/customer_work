package com.richard.fyoung.customeradmin.workbench.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内网工作台站点视图对象。{@code passwordMasked} 只保留末 4 位，{@code hasPassword} 标记是否配置了密码
 * （供前端决定"复制密码"按钮是否可用）；真实明文/密文永不出现在响应体中。
 * @author owlzhangfq@gmail.com
 */
@Data
public class WorkbenchSiteVO {
    private Long id;
    private String name;
    private String category;
    private String url;
    private String account;
    private String passwordMasked;
    private Boolean hasPassword;
    private String remark;
    private Boolean enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
