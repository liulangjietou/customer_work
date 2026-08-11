package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.stereotype.Component;

/**
 * 租户解析器（单一职责，全链路复用）。
 *
 * <p>约定 sessionId 可写成 {@code 租户ID<分隔符>会话ID}（分隔符取 {@code memory.tenant-delimiter}，默认 {@code :}），
 * 用于跨会话共享长期记忆 / 按租户分文件落盘等。此前该解析逻辑在 {@code CustomerServiceAgentFactory}、
 * {@code QualityFeedbackRecorder} 各复制了一份；本类收敛为唯一实现，供二者与故障诊断链路统一调用，
 * 遵循"防御式编程只保留一处、高复用"的约定。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class TenantResolver {

    /** 无法解析时的兜底租户。 */
    public static final String DEFAULT_TENANT = "default";

    private final CustomerWorkProperties properties;

    public TenantResolver(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    /**
     * 从 sessionId 解析租户 ID：命中分隔符取前缀，否则整体即租户，空白回退 {@link #DEFAULT_TENANT}。
     */
    public String resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_TENANT;
        }
        String delimiter = properties.getMemory().getTenantDelimiter();
        if (delimiter != null && !delimiter.isEmpty()) {
            int idx = sessionId.indexOf(delimiter);
            if (idx > 0) {
                return sessionId.substring(0, idx);
            }
        }
        return sessionId;
    }
}
