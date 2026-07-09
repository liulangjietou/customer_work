package com.richard.fyoung.customeradmin.auth.service;

/**
 * LDAP Bind 校验结果。
 * @author owlzhangfq@gmail.com
 */
public enum LdapBindResult {
    /** 账号密码校验通过。 */
    SUCCESS,
    /** 用户名或密码错误（AD 返回 AcceptSecurityContext data 52e 等鉴权失败）。 */
    INVALID_CREDENTIALS,
    /** AD 域控不可达/连接超时/其它协议异常，非用户名密码本身问题。 */
    SERVICE_UNAVAILABLE
}
