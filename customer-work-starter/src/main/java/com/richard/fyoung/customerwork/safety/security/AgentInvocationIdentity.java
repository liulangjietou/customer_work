package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;

/**
 * 单次 Agent 调用的可信主体快照。
 *
 * <p>身份只允许从鉴权入口写入的 {@link QuotaSubjectContext} 捕获，不能从 sessionId、请求正文或
 * 工具参数反推。快照随 {@code RuntimeContext} 固化后，即使模型调用跨线程、工具执行发生在稍后阶段，
 * 授权判断仍使用进入 Agent 时的同一主体事实。</p>
 *
 * @param tenantId   鉴权凭据解析出的租户
 * @param subjectType 主体类型
 * @param subjectId  用户 ID、后台用户 ID、API Key 指纹或匿名 IP
 * @param authenticated 是否为已认证主体；IP 只代表匿名来源，不能伪装成登录身份
 * @param accessEpoch 鉴权时冻结的租户访问版本；无租户门禁的兼容入口可为 null
 * @param channelCode 服务端确定的调用渠道，不从模型工具参数反推
 * @param sessionId 本次 Agent 调用的真实会话
 * @param agentCode 本次实际执行的 Agent
 * @author owlzhangfq@gmail.com
 */
public record AgentInvocationIdentity(String tenantId,
                                      QuotaSubjectType subjectType,
                                      String subjectId,
                                      boolean authenticated,
                                      Long accessEpoch,
                                      String channelCode,
                                      String sessionId,
                                      String agentCode) {

    public static final String CHANNEL_USER_HTTP = "user-http";
    public static final String CHANNEL_USER_WS = "user-ws";
    public static final String CHANNEL_API = "api";
    public static final String CHANNEL_ADMIN = "admin";
    public static final String CHANNEL_A2A = "a2a";
    public static final String CHANNEL_INTERNAL = "internal";

    /** 保留旧构造器；此时只建立入口主体，调用维度由 Agent 入口继续冻结。 */
    public AgentInvocationIdentity(String tenantId, QuotaSubjectType subjectType,
                                   String subjectId, boolean authenticated) {
        this(tenantId, subjectType, subjectId, authenticated, null, null, null, null);
    }

    /** 带租户访问版本的可信入口主体。 */
    public AgentInvocationIdentity(String tenantId, QuotaSubjectType subjectType,
                                   String subjectId, boolean authenticated, Long accessEpoch) {
        this(tenantId, subjectType, subjectId, authenticated, accessEpoch, null, null, null);
    }

    /**
     * 在进入 Agent 的唯一时刻冻结渠道、会话与 Agent；工具层只读该快照。
     */
    public AgentInvocationIdentity forInvocation(String channel, String invocationSessionId,
                                                 String invocationAgentCode) {
        return new AgentInvocationIdentity(tenantId, subjectType, subjectId, authenticated, accessEpoch,
            normalize(channel, CHANNEL_INTERNAL), normalize(invocationSessionId, "default"),
            normalize(invocationAgentCode, "unknown-agent"));
    }

    /** 仅补充服务端已验证的渠道，会话与 Agent 由后续装配器冻结。 */
    public AgentInvocationIdentity withChannel(String channel) {
        return new AgentInvocationIdentity(tenantId, subjectType, subjectId, authenticated, accessEpoch,
            normalize(channel, CHANNEL_INTERNAL), sessionId, agentCode);
    }

    /**
     * 使用服务端已经校验的下游主体替换入口凭据指纹。调用方不得把未校验的请求字段直接传入。
     */
    public AgentInvocationIdentity withTrustedSubjectId(String trustedSubjectId) {
        return new AgentInvocationIdentity(tenantId, subjectType,
            normalize(trustedSubjectId, subjectId), authenticated, accessEpoch,
            channelCode, sessionId, agentCode);
    }

    /** 用于状态/记忆分区的稳定主体键，不包含客户端可控会话号。 */
    public String subjectScope() {
        return normalize(tenantId, "default") + "::" + subjectType.name()
            + "::" + normalize(subjectId, "unknown");
    }

    /**
     * 从当前可信接入上下文捕获；没有主体时返回 null，由敏感工具授权层 fail closed。
     */
    public static AgentInvocationIdentity capture() {
        return AgentInvocationIdentityContext.get();
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
