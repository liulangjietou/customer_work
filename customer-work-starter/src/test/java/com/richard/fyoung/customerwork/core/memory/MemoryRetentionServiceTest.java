package com.richard.fyoung.customerwork.core.memory;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 长期记忆保留策略的时间边界、批量上限与跨租户治理作用域测试。 */
class MemoryRetentionServiceTest {

    @Test
    void cleanup_shouldUseConfiguredCutoffsAndExplicitCrossTenantScope() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getMemory().setRetentionDays(30);
        properties.getMemory().setWithdrawnConsentRetentionDays(365);
        properties.getMemory().setRetentionCleanupBatchSize(250);
        LongTermMemoryMapper longTermMapper = mock(LongTermMemoryMapper.class);
        FactLogMapper factLogMapper = mock(FactLogMapper.class);
        MemoryConsentMapper consentMapper = mock(MemoryConsentMapper.class);
        long now = 2_000_000_000_000L;
        long memoryCutoff = now - Duration.ofDays(30).toMillis();
        long consentCutoff = now - Duration.ofDays(365).toMillis();

        when(longTermMapper.deleteExpiredBefore(memoryCutoff, 250)).thenAnswer(invocation -> {
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine("memory-retention"));
            return 3;
        });
        when(factLogMapper.deleteExpiredBefore(memoryCutoff, 250)).thenReturn(4);
        when(consentMapper.deleteWithdrawnBefore(consentCutoff, 250)).thenReturn(2);
        MemoryRetentionService service = service(properties, longTermMapper, factLogMapper, consentMapper);
        service.afterPropertiesSet();

        MemoryRetentionService.CleanupResult result = service.cleanup(now);

        assertEquals(3, result.longTermDeleted());
        assertEquals(4, result.factLogDeleted());
        assertEquals(2, result.consentDeleted());
        assertEquals(9, result.totalDeleted());
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("memory-retention"),
            "治理作用域结束后必须恢复租户过滤");
    }

    @Test
    void runCleanup_whenDisabled_shouldNotTouchMappers() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getMemory().setRetentionCleanupEnabled(false);
        LongTermMemoryMapper longTermMapper = mock(LongTermMemoryMapper.class);
        FactLogMapper factLogMapper = mock(FactLogMapper.class);
        MemoryConsentMapper consentMapper = mock(MemoryConsentMapper.class);
        MemoryRetentionService service = service(properties, longTermMapper, factLogMapper, consentMapper);

        service.afterPropertiesSet();
        service.runCleanup();

        verify(longTermMapper, never()).deleteExpiredBefore(anyLong(), anyInt());
        verify(factLogMapper, never()).deleteExpiredBefore(anyLong(), anyInt());
        verify(consentMapper, never()).deleteWithdrawnBefore(anyLong(), anyInt());
    }

    @Test
    void invalidEnabledPolicy_shouldFailFast() {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getMemory().setRetentionDays(0);
        MemoryRetentionService service = service(properties, null, null, null);

        assertThrows(IllegalStateException.class, service::afterPropertiesSet);
    }

    private MemoryRetentionService service(CustomerWorkProperties properties,
                                           LongTermMemoryMapper longTermMapper,
                                           FactLogMapper factLogMapper,
                                           MemoryConsentMapper consentMapper) {
        return new MemoryRetentionService(properties, provider(longTermMapper),
            provider(factLogMapper), provider(consentMapper));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
