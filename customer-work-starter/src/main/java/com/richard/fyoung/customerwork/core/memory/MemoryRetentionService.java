package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.MemoryProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 按服务端保留策略分批清理长期记忆、事实与已撤回同意记录。 */
@Service
public class MemoryRetentionService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetentionService.class);

    private final MemoryProperties properties;
    private final LongTermMemoryMapper longTermMemoryMapper;
    private final FactLogMapper factLogMapper;
    private final MemoryConsentMapper consentMapper;

    public MemoryRetentionService(CustomerWorkProperties properties,
                                  ObjectProvider<LongTermMemoryMapper> longTermMemoryMapperProvider,
                                  ObjectProvider<FactLogMapper> factLogMapperProvider,
                                  ObjectProvider<MemoryConsentMapper> consentMapperProvider) {
        this.properties = properties.getMemory();
        this.longTermMemoryMapper = longTermMemoryMapperProvider.getIfAvailable();
        this.factLogMapper = factLogMapperProvider.getIfAvailable();
        this.consentMapper = consentMapperProvider.getIfAvailable();
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isRetentionCleanupEnabled()) {
            return;
        }
        if (properties.getRetentionDays() <= 0
            || properties.getWithdrawnConsentRetentionDays() <= 0
            || properties.getRetentionCleanupIntervalMs() <= 0
            || properties.getRetentionCleanupBatchSize() <= 0) {
            throw new IllegalStateException("memory retention policy values must be positive");
        }
    }

    @Scheduled(fixedDelayString = "${customer-work.memory.retention-cleanup-interval-ms:3600000}")
    public void runCleanup() {
        if (!properties.isRetentionCleanupEnabled()) {
            return;
        }
        try {
            CleanupResult result = cleanup(System.currentTimeMillis());
            if (result.totalDeleted() > 0) {
                log.info("memory retention cleanup completed, l2={}, l3={}, consent={}",
                    result.longTermDeleted(), result.factLogDeleted(), result.consentDeleted());
            }
        } catch (RuntimeException e) {
            log.error("memory retention cleanup failed, code={}", "MEMORY-RETENTION-CLEANUP-FAIL", e);
        }
    }

    /** 单轮清理；包级可见的时间参数让边界测试保持确定性。 */
    CleanupResult cleanup(long nowMs) {
        long memoryCutoff = nowMs - Duration.ofDays(properties.getRetentionDays()).toMillis();
        long consentCutoff = nowMs
            - Duration.ofDays(properties.getWithdrawnConsentRetentionDays()).toMillis();
        int batchSize = properties.getRetentionCleanupBatchSize();

        return CrossTenantOperations.execute(() -> new CleanupResult(
            longTermMemoryMapper == null ? 0 : longTermMemoryMapper.deleteExpiredBefore(memoryCutoff, batchSize),
            factLogMapper == null ? 0 : factLogMapper.deleteExpiredBefore(memoryCutoff, batchSize),
            consentMapper == null ? 0 : consentMapper.deleteWithdrawnBefore(consentCutoff, batchSize)));
    }

    record CleanupResult(int longTermDeleted, int factLogDeleted, int consentDeleted) {
        int totalDeleted() {
            return longTermDeleted + factLogDeleted + consentDeleted;
        }
    }
}
