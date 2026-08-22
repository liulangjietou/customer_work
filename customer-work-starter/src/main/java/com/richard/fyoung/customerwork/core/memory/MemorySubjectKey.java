package com.richard.fyoung.customerwork.core.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 长期记忆主体键：租户、主体和 Agent 四个维度缺一不可。
 *
 * <p>存储分区与外部 Provider userId 使用规范串的 SHA-256，不把真实用户标识传播给第三方，
 * 也避免任一字段里的分隔符造成键碰撞。原始字段只用于本地同意记录与授权判断。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public record MemorySubjectKey(String tenantId,
                               MemorySubjectType subjectType,
                               String subjectId,
                               String agentId) {

    private static final String SCOPE_PREFIX = "msk:";

    public MemorySubjectKey {
        tenantId = requireText(tenantId, "tenantId");
        subjectType = Objects.requireNonNull(subjectType, "subjectType");
        subjectId = requireText(subjectId, "subjectId");
        agentId = requireText(agentId, "agentId");
    }

    /** 本地 L2/L3 记忆分区键。 */
    public String scopeId() {
        return SCOPE_PREFIX + digest(canonical());
    }

    /** 外部记忆服务使用的去标识化 userId。 */
    public String providerUserId() {
        return scopeId();
    }

    private String canonical() {
        return component(tenantId) + component(subjectType.name())
            + component(subjectId) + component(agentId);
    }

    private String component(String value) {
        return value.length() + ":" + value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return value.trim();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
