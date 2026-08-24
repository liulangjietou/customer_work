package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.properties.SecurityProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 结构化/兼容 API Key 的唯一解析与授权入口。 */
final class ApiKeyCredentialResolver {

    private static final byte[] INVALID_HASH = new byte[32];
    private static final String WILDCARD = "*";

    private final Clock clock;

    ApiKeyCredentialResolver(Clock clock) {
        this.clock = clock;
    }

    Resolution resolve(String keyId, String secret, String method, String path,
                       SecurityProperties.Auth auth) {
        if (!StringUtils.hasText(secret)) {
            return Resolution.invalid();
        }
        if (StringUtils.hasText(keyId)) {
            return resolveStructured(keyId.trim(), secret, method, path, auth);
        }
        return resolveLegacy(secret, auth);
    }

    private Resolution resolveStructured(String keyId, String secret, String method, String path,
                                         SecurityProperties.Auth auth) {
        byte[] providedHash = hexBytes(ApiKeySecretHasher.sha256Hex(secret));
        SecurityProperties.Credential matched = null;
        int matches = 0;
        List<SecurityProperties.Credential> credentials = auth.getCredentials();
        if (credentials != null) {
            for (SecurityProperties.Credential credential : credentials) {
                byte[] configuredHash = configuredHash(credential);
                boolean secretMatches = MessageDigest.isEqual(providedHash, configuredHash);
                if (credential != null && keyId.equals(credential.getKeyId()) && secretMatches
                    && ApiKeySecretHasher.isSha256Hex(credential.getKeyHash())) {
                    matched = credential;
                    matches++;
                }
            }
        }
        if (matches != 1 || matched == null || !matched.isEnabled()
            || !TenantContext.isValidTenantId(matched.getTenantId())) {
            return Resolution.invalid();
        }
        long minimumEpoch = minimumEpoch(auth.getMinimumEpochs(), keyId);
        if (matched.getEpoch() <= 0L || matched.getEpoch() < minimumEpoch || isExpired(matched.getExpiresAt())) {
            return Resolution.invalid();
        }
        Set<String> scopes = normalizedScopes(matched.getScopes());
        ApiKeyPrincipal principal = new ApiKeyPrincipal(keyId, matched.getTenantId(), matched.getEpoch(), scopes);
        return allows(scopes, method, path) ? Resolution.allowed(principal) : Resolution.scopeDenied(principal);
    }

    private Resolution resolveLegacy(String secret, SecurityProperties.Auth auth) {
        byte[] provided = secret.getBytes(StandardCharsets.UTF_8);
        String matchedTenant = null;
        Map<String, String> tenantKeys = auth.getTenantKeys();
        if (tenantKeys != null) {
            for (Map.Entry<String, String> entry : tenantKeys.entrySet()) {
                if (constantTimeEquals(provided, entry.getKey())) {
                    matchedTenant = entry.getValue();
                }
            }
        }
        List<String> validKeys = auth.getApiKeys();
        if (validKeys != null) {
            for (String candidate : validKeys) {
                if (constantTimeEquals(provided, candidate) && matchedTenant == null) {
                    matchedTenant = TenantContext.DEFAULT;
                }
            }
        }
        if (!TenantContext.isValidTenantId(matchedTenant)) {
            return Resolution.invalid();
        }
        String fingerprint = ApiKeySecretHasher.sha256Hex(secret).substring(0, 16);
        return Resolution.allowed(new ApiKeyPrincipal("legacy-" + fingerprint, matchedTenant, 0L,
            Set.of(WILDCARD)));
    }

    private byte[] configuredHash(SecurityProperties.Credential credential) {
        if (credential == null || !ApiKeySecretHasher.isSha256Hex(credential.getKeyHash())) {
            return INVALID_HASH;
        }
        return hexBytes(credential.getKeyHash());
    }

    private byte[] hexBytes(String value) {
        try {
            return java.util.HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return INVALID_HASH;
        }
    }

    private long minimumEpoch(Map<String, Long> minimumEpochs, String keyId) {
        if (minimumEpochs == null || minimumEpochs.get(keyId) == null) {
            return 0L;
        }
        return minimumEpochs.get(keyId);
    }

    private boolean isExpired(Instant expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(clock.instant());
    }

    private Set<String> normalizedScopes(List<String> scopes) {
        if (CollectionUtils.isEmpty(scopes)) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (StringUtils.hasText(scope)) {
                normalized.add(scope.trim());
            }
        }
        return normalized;
    }

    private boolean allows(Set<String> scopes, String method, String path) {
        for (String scope : scopes) {
            if (WILDCARD.equals(scope)) {
                return true;
            }
            String pathPattern = scope;
            int separator = scope.indexOf(':');
            if (separator > 0) {
                String scopedMethod = scope.substring(0, separator).toUpperCase(Locale.ROOT);
                if (!scopedMethod.equals(method == null ? "" : method.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                pathPattern = scope.substring(separator + 1);
            }
            if (matchesPath(pathPattern, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPath(String pattern, String path) {
        if (!StringUtils.hasText(pattern) || !StringUtils.hasText(path)) {
            return false;
        }
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(pattern);
    }

    private boolean constantTimeEquals(byte[] provided, String candidate) {
        return candidate != null
            && MessageDigest.isEqual(provided, candidate.getBytes(StandardCharsets.UTF_8));
    }

    enum Status {
        ALLOWED,
        INVALID,
        SCOPE_DENIED
    }

    record Resolution(Status status, ApiKeyPrincipal principal) {
        static Resolution allowed(ApiKeyPrincipal principal) {
            return new Resolution(Status.ALLOWED, principal);
        }

        static Resolution invalid() {
            return new Resolution(Status.INVALID, null);
        }

        static Resolution scopeDenied(ApiKeyPrincipal principal) {
            return new Resolution(Status.SCOPE_DENIED, principal);
        }
    }
}
