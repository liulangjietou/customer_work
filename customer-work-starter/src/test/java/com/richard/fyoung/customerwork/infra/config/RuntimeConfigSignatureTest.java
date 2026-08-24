package com.richard.fyoung.customerwork.infra.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigSignatureTest {

    private static final String SECRET = "runtime-signing-secret-at-least-32-bytes";

    @Test
    void signedMetadata_shouldVerifyWithMatchingKeyId() {
        CustomerWorkRuntimeConfig config = signedConfig();

        assertTrue(RuntimeConfigSignature.verify(config, Map.of("key-1", SECRET), true));
    }

    @Test
    void tamperedRevisionOrContentHash_shouldFailVerification() {
        CustomerWorkRuntimeConfig config = signedConfig();
        config.setRevision("revision-2");
        assertFalse(RuntimeConfigSignature.verify(config, Map.of("key-1", SECRET), true));

        config = signedConfig();
        config.setContentHash("b".repeat(64));
        assertFalse(RuntimeConfigSignature.verify(config, Map.of("key-1", SECRET), true));
    }

    @Test
    void unknownKeyOrPartialSignature_shouldFailClosed() {
        CustomerWorkRuntimeConfig config = signedConfig();
        assertFalse(RuntimeConfigSignature.verify(config, Map.of("key-2", SECRET), true));
        config.setSignature(null);
        assertFalse(RuntimeConfigSignature.verify(config, Map.of("key-1", SECRET), false));
    }

    @Test
    void unsignedLegacyConfigIsAcceptedOnlyWhenSignatureIsOptional() {
        CustomerWorkRuntimeConfig config = new CustomerWorkRuntimeConfig();
        assertTrue(RuntimeConfigSignature.verify(config, Map.of(), false));
        assertFalse(RuntimeConfigSignature.verify(config, Map.of(), true));
    }

    private CustomerWorkRuntimeConfig signedConfig() {
        CustomerWorkRuntimeConfig config = new CustomerWorkRuntimeConfig();
        config.setRevision("revision-1");
        config.setContentHash("a".repeat(64));
        config.setPublishedAt("2026-08-24T10:00:00");
        config.setSignatureKeyId("key-1");
        config.setSignatureAlgorithm(RuntimeConfigSignature.ALGORITHM);
        config.setSignature(RuntimeConfigSignature.sign(config, SECRET));
        return config;
    }
}
