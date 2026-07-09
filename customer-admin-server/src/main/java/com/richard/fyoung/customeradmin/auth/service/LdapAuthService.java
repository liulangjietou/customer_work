package com.richard.fyoung.customeradmin.auth.service;

import com.richard.fyoung.customeradmin.auth.config.AdminLdapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

/**
 * OA 域账号（LDAP/AD）密码校验。
 *
 * <p>直接向企业 AD 域控发起一次匿名 Simple Bind：Bind 成功即代表账号密码正确（AD 侧真正的鉴权
 * 由域控完成，本服务不做密码比对/不落库密码），失败通过 {@link NamingException} 子类型区分是
 * "用户名密码错误"还是"域控不可达"，避免把网络故障误报成登录失败。</p>
 *
 * <p>实现手法与既有生产项目 gmcf-operate-web 的 {@code LdapLoginAdapter} 保持一致（JNDI
 * {@code InitialDirContext} + simple 认证），仅将域控地址/UPN 后缀改为可配置项。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class LdapAuthService {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthService.class);

    private final AdminLdapProperties properties;

    public LdapAuthService(AdminLdapProperties properties) {
        this.properties = properties;
    }

    /**
     * @param username 不含域名后缀的登录名（如 zhangfuqiang3）；若用户输入时已带 {@code @xxx}，
     *                  由调用方（AuthService）先行归一化再传入。
     */
    public LdapBindResult bind(String username, String password) {
        String userPrincipal = username + properties.getDomainSuffix();

        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, properties.getUrl());
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, userPrincipal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(properties.getConnectTimeoutMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(properties.getConnectTimeoutMillis()));

        try {
            InitialDirContext ctx = new InitialDirContext(env);
            ctx.close();
            return LdapBindResult.SUCCESS;
        } catch (AuthenticationException e) {
            // AD 典型返回：AcceptSecurityContext error, data 52e（用户名或密码错误/账号禁用等）
            log.info("LDAP bind failed, invalid credentials, principal={}", userPrincipal);
            return LdapBindResult.INVALID_CREDENTIALS;
        } catch (NamingException e) {
            // 连接超时/域控不可达/协议异常等，非用户名密码问题
            log.error("LDAP bind error, service unavailable, principal={}, url={}",
                userPrincipal, properties.getUrl(), e);
            return LdapBindResult.SERVICE_UNAVAILABLE;
        }
    }
}
