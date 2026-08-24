package com.richard.fyoung.customeradmin.workspace.memory;

import com.richard.fyoung.customeradmin.workspace.runtime.WorkspaceRuntimeScope;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Admin/Harness 长期记忆的可信主体分区。
 *
 * <p>分区只从鉴权入口的 {@link AgentInvocationIdentity} 构建，不使用客户端可控
 * sessionId。数据库键和目录只保留主体摘要，避免外部用户 ID 泄露到路径/日志。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public record AgentMemoryScope(String agentCode, String subjectHash, boolean trusted) {

    private static final String SUBJECT_MARKER = "::subject::";

    public static AgentMemoryScope current(String agentCode) {
        AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
        if (identity == null) {
            // 启动期/离线单测没有请求主体，保持原路径；生产 HTTP 入口均会建立身份。
            return new AgentMemoryScope(agentCode, null, false);
        }
        String material = identity.subjectScope();
        return new AgentMemoryScope(agentCode, digest(material), true);
    }

    /** Agent 实例缓存与记忆权威存储的分区键。 */
    public String storageKey() {
        String base = WorkspaceRuntimeScope.agent(agentCode);
        return trusted ? base + SUBJECT_MARKER + subjectHash : base;
    }

    /** 框架短期状态的 userId，与长期记忆使用同一主体边界。 */
    public String stateUserId() {
        return storageKey();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
