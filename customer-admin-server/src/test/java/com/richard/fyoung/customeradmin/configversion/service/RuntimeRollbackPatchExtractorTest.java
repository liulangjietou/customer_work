package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRollbackPatchExtractorTest {

    private final RuntimeRollbackPatchExtractor extractor =
        new RuntimeRollbackPatchExtractor(new ObjectMapper());

    @Test
    void extract_shouldKeepOnlyPromptAndMaxIters() {
        String source = """
            {"systemPrompt":"safe","agent":{"maxIters":9,"other":"drop"},
             "model":{"apiKeyCipher":"SECRET"},
             "mcpServers":[{"headers":{"Authorization":"TOKEN"}}],
             "routingPolicy":{"policyContentHash":"route"},
             "onlineExperiment":{"assignmentSalt":"salt"}}
            """;

        String patchJson = extractor.serialize(extractor.extract(source));

        assertTrue(patchJson.contains("safe"));
        assertTrue(patchJson.contains("\"maxIters\":9"));
        assertFalse(patchJson.contains("SECRET"));
        assertFalse(patchJson.contains("TOKEN"));
        assertFalse(patchJson.contains("route"));
        assertFalse(patchJson.contains("salt"));
    }

    @Test
    void deserialize_shouldRejectAnyFieldOutsideWhitelist() {
        assertThrows(IllegalStateException.class, () -> extractor.deserialize(
            "{\"systemPrompt\":\"safe\",\"maxIters\":9,\"apiKeyCipher\":\"SECRET\"}"));
        assertThrows(IllegalStateException.class, () -> extractor.deserialize(
            "{\"systemPrompt\":\"safe\"}"));
    }

    @Test
    void extract_shouldPreserveExplicitNullsAndRejectMissingPaths() {
        RuntimeRollbackPatch patch = extractor.extract(
            "{\"systemPrompt\":null,\"agent\":{\"maxIters\":null}}");

        assertEquals(new RuntimeRollbackPatch(null, null), patch);
        assertThrows(BizException.class,
            () -> extractor.extract("{\"systemPrompt\":\"x\",\"agent\":{}}"));
    }

    @Test
    void verifyContentHash_shouldRejectMismatch() {
        String actual = extractor.verifyContentHash("{}", null);

        assertEquals(64, actual.length());
        assertThrows(BizException.class,
            () -> extractor.verifyContentHash("{}", "0".repeat(64)));
    }

    @Test
    void maxIters_shouldAcceptDomainBoundariesAndRejectOutOfRangeHistory() {
        assertEquals(1, extractor.extract(
            "{\"systemPrompt\":\"safe\",\"agent\":{\"maxIters\":1}}"
        ).maxIters());
        assertEquals(100, extractor.deserialize(
            "{\"systemPrompt\":\"safe\",\"maxIters\":100}"
        ).maxIters());

        assertThrows(BizException.class, () -> extractor.extract(
            "{\"systemPrompt\":\"safe\",\"agent\":{\"maxIters\":0}}"));
        assertThrows(IllegalStateException.class, () -> extractor.deserialize(
            "{\"systemPrompt\":\"safe\",\"maxIters\":101}"));
    }
}
