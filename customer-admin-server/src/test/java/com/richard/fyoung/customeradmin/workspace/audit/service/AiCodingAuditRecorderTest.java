package com.richard.fyoung.customeradmin.workspace.audit.service;

import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.mapper.AiCodingAuditLogMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiCodingAuditRecorder} 单测：落库委托、createTime 兜底补全、
 * 落库失败只记日志不向业务主链路抛异常（审计是旁路能力）。
 * @author owlzhangfq@gmail.com
 */
class AiCodingAuditRecorderTest {

    private AiCodingAuditLogMapper mapper;
    private AiCodingAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(AiCodingAuditLogMapper.class);
        recorder = new AiCodingAuditRecorder(mapper);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void persist_shouldInsert_andBackfillCreateTime() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        recorder.persist(entry);

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertNotNull(captor.getValue().getCreateTime());
    }

    @Test
    void persist_shouldRestoreCapturedTenantAroundMapperInsert() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        entry.setTenantContextId("tenant-a");
        AtomicReference<String> insertTenant = new AtomicReference<>();
        when(mapper.insert(entry)).thenAnswer(invocation -> {
            insertTenant.set(TenantContext.get());
            return 1;
        });

        TenantContext.runWith("worker-previous", () -> {
            recorder.persist(entry);
            assertEquals("worker-previous", TenantContext.get(),
                "异步线程原有上下文必须在落库后恢复");
        });

        assertEquals("tenant-a", insertTenant.get(),
            "Mapper insert 必须运行在审计开始时捕获的租户下");
    }

    @Test
    void persist_shouldRestoreCapturedTenantThroughSpringAsyncProxy() throws Exception {
        try (AnnotationConfigApplicationContext context =
                 new AnnotationConfigApplicationContext(AsyncTestConfig.class)) {
            AiCodingAuditLogMapper asyncMapper = context.getBean(AiCodingAuditLogMapper.class);
            AtomicReference<String> insertTenant = new AtomicReference<>();
            CountDownLatch inserted = new CountDownLatch(1);
            when(asyncMapper.insert(any(AiCodingAuditLog.class))).thenAnswer(invocation -> {
                insertTenant.set(TenantContext.get());
                inserted.countDown();
                return 1;
            });
            AiCodingAuditLog entry = new AiCodingAuditLog();
            entry.setTenantContextId("tenant-a");
            TenantContext.set("caller-tenant");

            context.getBean(AiCodingAuditRecorder.class).persist(entry);
            TenantContext.clear();

            assertTrue(inserted.await(2, TimeUnit.SECONDS), "@Async 审计应在超时前完成落库");
            assertEquals("tenant-a", insertTenant.get(), "@Async 代理后的真实工作线程必须恢复租户快照");
        }
    }

    @Test
    void persist_shouldSwallowPersistenceFailure() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        entry.setTenantContextId("tenant-a");
        when(mapper.insert(any(AiCodingAuditLog.class))).thenThrow(new RuntimeException("db down"));

        TenantContext.runWith("worker-previous", () -> {
            assertDoesNotThrow(() -> recorder.persist(entry));
            assertEquals("worker-previous", TenantContext.get(),
                "Mapper 异常也不能污染异步线程后续任务的租户上下文");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncTestConfig {

        @Bean
        AiCodingAuditLogMapper auditLogMapper() {
            return mock(AiCodingAuditLogMapper.class);
        }

        @Bean
        AiCodingAuditRecorder auditRecorder(AiCodingAuditLogMapper auditLogMapper) {
            return new AiCodingAuditRecorder(auditLogMapper);
        }

        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(8);
            executor.setThreadNamePrefix("audit-test-");
            return executor;
        }
    }
}
