package com.richard.fyoung.customerwork.data.calllog;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreAgentCallRecordSinkTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void emit_shouldRestoreSubmittingTenantInsideAsyncStore_andNotLeakWorkerContext() throws Exception {
        AgentCallLogStore store = mock(AgentCallLogStore.class);
        AgentCallRecord record = mock(AgentCallRecord.class);
        when(record.requestId()).thenReturn("req-1");
        CountDownLatch saved = new CountDownLatch(2);
        AtomicReference<String> firstTenant = new AtomicReference<>();
        AtomicReference<String> secondTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            if (firstTenant.get() == null) {
                firstTenant.set(TenantContext.get());
            } else {
                secondTenant.set(TenantContext.get());
            }
            saved.countDown();
            return invocation.getArgument(0);
        }).when(store).save(any(AgentCallRecord.class));

        StoreAgentCallRecordSink sink = new StoreAgentCallRecordSink(store);
        try {
            TenantContext.set("tenant-a");
            sink.emit(record);
            TenantContext.set("tenant-b");
            sink.emit(record);
            TenantContext.clear();

            assertTrue(saved.await(5, TimeUnit.SECONDS));
            assertEquals("tenant-a", firstTenant.get());
            assertEquals("tenant-b", secondTenant.get());
            assertNull(TenantContext.get());
        } finally {
            sink.destroy();
        }
    }
}
