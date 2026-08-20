package com.richard.fyoung.customerwork.core.constant;

/**
 * 开发期默认凭据（生产必须覆盖）。
 *
 * <p>这些值同时出现在两个角色上：一边是<b>代码/yml 里的兜底默认值</b>，另一边是
 * <b>生产就绪校验器的黑名单</b>——校验器靠"当前值是否等于开发默认值"判断有没有人忘了改。
 * 两边此前各写一份字面量，改了默认值而漏改校验器的后果是：校验器再也检不出这一项，
 * 而它<b>照样返回通过</b>，等于生产环境揣着开发密钥上线且无人告警。</p>
 *
 * <p>集中在此不降低安全性——它们本来就明文躺在源码与 yml 里；集中反而让"哪些值属于必须覆盖"
 * 一目了然，便于审计。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class DevDefaultCredentials {

    /** 用户端 JWT 签名密钥的开发默认值。 */
    public static final String USER_JWT_SECRET = "dev-secret-change-me-in-production-0001";

    /** 服务间智能体访问密钥的开发默认值。 */
    public static final String AGENT_ACCESS_SECRET = "dev-agent-secret-change-me-0001";

    /** MinIO 的出厂默认账号与密码（accessKey 与 secretKey 同值）。 */
    public static final String MINIO_CREDENTIAL = "minioadmin";

    private DevDefaultCredentials() {
    }
}
