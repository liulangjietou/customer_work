package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 运行时配置 ACK 的实例级身份，配置格式为 {@code tenantId|instanceId|token}。
 *
 * <p>token 只用于常量时间比对，不落库、不返回接口；实例 ID 与租户由服务端身份映射决定，
 * 不能相信 ACK 请求正文中的自报值。</p>
 */
public record RuntimeAckIdentity(String tenantId, String instanceId, String token) {

    private static final int PART_COUNT = 3;

    public static Optional<RuntimeAckIdentity> parse(String configured) {
        if (!StringUtils.hasText(configured)) {
            return Optional.empty();
        }
        String[] parts = configured.split("\\|", -1);
        if (parts.length != PART_COUNT) {
            return Optional.empty();
        }
        String tenantId = parts[0].trim();
        String instanceId = parts[1].trim();
        String token = parts[2].trim();
        if (!TenantContext.isValidTenantId(tenantId)
            || !StringUtils.hasText(instanceId) || !StringUtils.hasText(token)) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeAckIdentity(tenantId, instanceId, token));
    }

    public boolean tokenMatches(String actual) {
        return StringUtils.hasText(actual) && MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
