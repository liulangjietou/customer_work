package com.richard.fyoung.customeradmin.aiconfig.secret.dto;

import java.time.LocalDateTime;

/** 安全可回显的凭据元数据，不包含密文、明文或明文片段。 */
public record SecretMetadataVO(Long refId,
                               String refCode,
                               String providerType,
                               Integer currentVersion,
                               String status,
                               LocalDateTime expiresAt,
                               LocalDateTime lastRotatedAt,
                               Long lastRotatedBy) {
}
