package com.richard.fyoung.customeradmin.workbench.dto;

import lombok.Data;

/**
 * 供 ScriptCat 通用脚本使用的站点凭证 + 自动登录配置。
 *
 * <p><b>这是全系统唯一返回明文密码的出口</b>，只有携带有效个人访问令牌的
 * {@code /api/workbench/agent/site} 接口可达，且每次读取都进操作审计。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class WorkbenchAgentSiteVO {
    private String account;
    /** 明文密码。 */
    private String password;
    private String usernameSelector;
    private String passwordSelector;
    private String submitSelector;
    private String fillMode;
    private String submitMode;
    private Integer initDelayMs;
    private Integer submitDelayMs;
}
