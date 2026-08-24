package com.richard.fyoung.customerwork.safety.security;

import java.util.Set;

/** 通过 API Key 鉴权后建立的非敏感调用方快照。 */
public record ApiKeyPrincipal(String keyId, String tenantId, long epoch, Set<String> scopes) {

    /** WebFlux exchange attribute 名，供下游限流、审计读取，不携带原始 secret。 */
    public static final String EXCHANGE_ATTRIBUTE = ApiKeyPrincipal.class.getName();

    public ApiKeyPrincipal {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
