package com.richard.fyoung.customerwork.safety.security;

/**
 * 客服端入口的统一鉴权路由口径。
 *
 * <p>浏览器用户、人工坐席和服务接入方持有不同类型的凭据，不能再由全局 API Key
 * 抢先拦截。所有过滤器共用这一份路由判定，避免新增接口时两份白名单发生漂移。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class CustomerSecurityPaths {

    private static final String AUTH_PREFIX = "/api/customer/auth/";
    private static final String USER_PREFIX = "/api/customer/user/";
    private static final String FEEDBACK_PREFIX = "/api/customer/feedback";
    private static final String CSAT_PREFIX = "/api/customer/csat/";
    private static final String CSAT_SUMMARY = "/api/customer/csat/summary";
    private static final String AGENT_PREFIX = "/api/customer/agent/";
    private static final String HANDOFF_PREFIX = "/api/customer/handoffs";

    private CustomerSecurityPaths() {
    }

    /** 不携带业务身份的公开端点。 */
    public static boolean isPublic(String path) {
        return path.startsWith("/actuator")
            || path.equals("/api/customer/health")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/webjars")
            || path.equals(AUTH_PREFIX + "register")
            || path.equals(AUTH_PREFIX + "login");
    }

    /** 由终端用户 Bearer JWT 保护的 HTTP 端点。 */
    public static boolean requiresUserJwt(String path) {
        return path.equals(AUTH_PREFIX + "me")
            || path.equals(AUTH_PREFIX + "avatar")
            || path.startsWith(USER_PREFIX)
            || path.equals("/api/customer/attachment")
            || path.equals(FEEDBACK_PREFIX)
            || path.startsWith(FEEDBACK_PREFIX + "/")
            || (path.startsWith(CSAT_PREFIX) && !path.equals(CSAT_SUMMARY));
    }

    /** 由坐席 HMAC 令牌保护的 HTTP 端点。 */
    public static boolean requiresAgentToken(String path) {
        return path.startsWith(AGENT_PREFIX)
            || path.equals(HANDOFF_PREFIX)
            || path.startsWith(HANDOFF_PREFIX + "/");
    }

    /** WebSocket 在握手处理器内校验查询参数中的短期令牌。 */
    public static boolean isCredentialedWebSocket(String path) {
        return path.equals("/ws/user") || path.equals("/ws/agent");
    }

    /** 是否应绕过服务接入方 API Key，由更具体的身份机制接管。 */
    public static boolean bypassesApiKey(String path) {
        return isPublic(path)
            || requiresUserJwt(path)
            || requiresAgentToken(path)
            || isCredentialedWebSocket(path);
    }
}
