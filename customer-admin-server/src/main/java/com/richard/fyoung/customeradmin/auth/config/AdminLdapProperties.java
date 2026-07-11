package com.richard.fyoung.customeradmin.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OA 域账号（LDAP/AD）单点登录连接参数。
 *
 * <p>企业 AD 域控地址/UPN 域名后缀因环境而异（生产由运维通过环境变量注入，见
 * application.yml 的 {@code ${ADMIN_LDAP_*}} 占位符），不同租户/域名后缀不要硬编码在代码里。</p>
 * <p>取值优先级：yml 显式配置 > 环境变量 {@code ADMIN_LDAP_URL} / {@code ADMIN_LDAP_DOMAIN_SUFFIX} > null。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.ldap")
public class AdminLdapProperties {

    /** 是否启用 OA 域账号登录入口；关闭后 /api/auth/sso-login 直接返回未开通提示，不发起 LDAP 请求。 */
    private boolean enabled = false;

    /** LDAP/AD 域控地址，如 ldap://domain:123。 */
    private String url;

    /** UPN 域名后缀（含 @），如 @com.cn；用户名不含 @ 时自动拼接后再向 AD 发起 Bind。 */
    private String domainSuffix;

    /** LDAP 连接/读取超时（毫秒），AD 不可达时避免请求长时间挂起拖垮登录接口。 */
    private int connectTimeoutMillis = 5000;

    /** 首次登录自动创建本地账号时默认分配的角色编码（对应 sys_role.role_code），逗号分隔多个。
     * 默认给 super_admin（超级管理员）——OA 域账号本身已经过企业 AD 域控鉴权，视为可信身份；
     * 如需收紧权限，生产环境可通过 ADMIN_LDAP_DEFAULT_ROLE_CODES 环境变量改回 operator 等受限角色。 */
    private List<String> defaultRoleCodes = List.of("super_admin");

    /**
     * Bean 初始化后，如果 yml 未配置 url / domainSuffix，则尝试从本地环境变量读取。
     * <p>这样开发者在本地只需 {@code export ADMIN_LDAP_URL=...} 即可生效，无需改 yml。</p>
     */
    @PostConstruct
    void fallbackFromEnv() {
        if (url == null || url.isBlank()) {
            String envUrl = System.getenv("ADMIN_LDAP_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                url = envUrl;
            }
        }
        if (domainSuffix == null || domainSuffix.isBlank()) {
            String envSuffix = System.getenv("ADMIN_LDAP_DOMAIN_SUFFIX");
            if (envSuffix != null && !envSuffix.isBlank()) {
                domainSuffix = envSuffix;
            }
        }
    }
}
