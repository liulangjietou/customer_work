package com.richard.fyoung.customerwork.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 长期记忆 SPI 新增治理能力时的下游兼容契约。 */
class LongTermMemoryStoreCompatibilityTest {

    @Test
    void legacyImplementation_shouldRemainLoadableAndFailFastOnlyWhenListingIsUsed() {
        LongTermMemoryStore legacyStore = new LongTermMemoryStore() {
            @Override
            public void add(String scopeId, String fact) {
            }

            @Override
            public List<String> recall(String scopeId, String query, int topK) {
                return List.of();
            }

            @Override
            public void clear(String scopeId) {
            }

            @Override
            public int size(String scopeId) {
                return 0;
            }
        };

        assertThrows(UnsupportedOperationException.class,
            () -> legacyStore.list("tenant-a:user-1", 10));
    }
}
