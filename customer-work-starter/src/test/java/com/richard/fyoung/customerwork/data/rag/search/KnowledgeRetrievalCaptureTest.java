package com.richard.fyoung.customerwork.data.rag.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrievalCaptureTest {

    private static final KnowledgeRetrievalSource SOURCE = new KnowledgeRetrievalSource(1, "退款知识库",
        "refund", "100", BigDecimal.ONE, new KnowledgeDocumentReference(30L, 7L, 10L, 100L));

    @Test
    void snapshotShouldKeepFirstResultPerAgentAndFreezeItsSources() {
        KnowledgeRetrievalCapture capture = new KnowledgeRetrievalCapture();
        List<KnowledgeRetrievalSource> sources = new ArrayList<>(List.of(SOURCE));
        KnowledgeRetrievalResult result = KnowledgeRetrievalResult.completed("私有正文", sources);
        sources.clear();
        capture.record("root", result);
        List<KnowledgeRetrievalCapture.Retrieval> first = capture.snapshot();
        capture.record("root", KnowledgeRetrievalResult.degraded(null));
        capture.record("child", KnowledgeRetrievalResult.skipped());

        assertEquals(1, first.size());
        assertEquals(List.of(SOURCE), first.get(0).sources());
        assertEquals(KnowledgeRetrievalResult.Status.HIT, first.get(0).status());
        assertEquals(List.of("root", "child"), capture.snapshot().stream().map(row -> row.agentCode()).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }

    @Test
    void nativeContextsShouldNotShareSourcesAcrossCalls() {
        RuntimeContext first = RuntimeContext.empty();
        RuntimeContext second = RuntimeContext.empty();
        KnowledgeRetrievalCapture firstCapture = new KnowledgeRetrievalCapture();
        KnowledgeRetrievalCapture secondCapture = new KnowledgeRetrievalCapture();
        KnowledgeRetrievalCapture.bind(first, firstCapture);
        KnowledgeRetrievalCapture.bind(second, secondCapture);

        KnowledgeRetrievalCapture.record(first, "agent", KnowledgeRetrievalResult.completed("正文", List.of(SOURCE)));
        KnowledgeRetrievalCapture.record(second, "agent", KnowledgeRetrievalResult.completed(null));
        KnowledgeRetrievalCapture.record(null, "unbound", KnowledgeRetrievalResult.skipped());

        assertEquals(List.of(SOURCE), firstCapture.snapshot().get(0).sources());
        assertEquals(KnowledgeRetrievalResult.Status.MISS, secondCapture.snapshot().get(0).status());
        assertTrue(secondCapture.snapshot().get(0).sources().isEmpty());
    }

    @Test
    void snapshotJsonShouldRoundTripReferencesWithoutTheRetrievedBody() throws Exception {
        KnowledgeRetrievalCapture capture = new KnowledgeRetrievalCapture();
        capture.record("agent", KnowledgeRetrievalResult.degraded("正文不可另存：private-body", List.of(SOURCE)));
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(capture.snapshot());
        List<KnowledgeRetrievalCapture.Retrieval> restored = mapper.readValue(json, new TypeReference<>() {});

        assertFalse(json.contains("private-body"));
        assertEquals(capture.snapshot(), restored);
        assertTrue(restored.get(0).sources().get(0).documentReference().complete());
    }

    @Test
    void legacyConstructorsShouldNotInventManagedReferences() {
        KnowledgeNode node = new KnowledgeNode("external", "【知识来源】revision_id=10", BigDecimal.ONE, "d", "c");
        assertNull(KnowledgeRetrievalSource.from(1, node).documentReference());
        assertTrue(new KnowledgeRetrievalResult("正文", KnowledgeRetrievalResult.Status.HIT).sources().isEmpty());
        assertFalse(new KnowledgeDocumentReference(30L, null, 10L, 100L).complete());
    }
}
