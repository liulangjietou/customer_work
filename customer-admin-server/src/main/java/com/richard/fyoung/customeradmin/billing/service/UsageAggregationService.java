package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.config.BillingSettlementProperties;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import com.richard.fyoung.customeradmin.billing.event.UsageAggregationCompletedEvent;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGatewayProvider;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallCost;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用量归集：从客服端真实 MODEL 分段读取已冻结金额，在数据库自然日锁内整体重建日账单。
 *
 * <p>归集不再按“当天最终价”二次计算。每段金额已经在调用结束时按当时冻结的价目结算，
 * 日账单、call/session 成本和业务结果因此共享同一份不可变事实。</p>
 */
@Slf4j
@Service
public class UsageAggregationService {

    private final CwTenantUsageDailyMapper usageMapper;
    private final CustomerUsageFactGatewayProvider sourceProvider;
    private final BillingSettlementProperties settlementProperties;
    private final ApplicationEventPublisher eventPublisher;

    public UsageAggregationService(CwTenantUsageDailyMapper usageMapper,
                                   CustomerUsageFactGatewayProvider sourceProvider,
                                   BillingSettlementProperties settlementProperties,
                                   ApplicationEventPublisher eventPublisher) {
        this.usageMapper = usageMapper;
        this.sourceProvider = sourceProvider;
        this.settlementProperties = settlementProperties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 归集指定自然日。多副本先锁定日期，再冻结客服端调用 ID 上界并整体替换当天派生账单。
     *
     * @return 写入的日账单分组数
     */
    @Transactional(rollbackFor = Exception.class)
    public int aggregate(LocalDate statDate) {
        LocalDate target = statDate == null
            ? LocalDate.now(settlementProperties.zone()).minusDays(1) : statDate;
        usageMapper.ensureAggregationLock(target);
        LocalDate lockedDate = usageMapper.lockAggregationDate(target);
        if (!target.equals(lockedDate)) {
            throw new IllegalStateException("failed to acquire usage aggregation date lock: " + target);
        }

        SettlementWindow window = window(target, settlementProperties.zone());
        long sourceMaxCallLogId = CrossTenantOperations.execute(() -> sourceProvider.get().mapper()
            .maxCallLogId(null, window.fromMs(), window.toMs()));
        List<UsageAggregate> rows = sourceMaxCallLogId == 0L ? List.of()
            : CrossTenantOperations.execute(() -> sourceProvider.get().mapper()
                .aggregate(null, window.fromMs(), window.toMs(), sourceMaxCallLogId));
        rows = rows == null ? List.of() : rows;

        Set<String> affectedTenants = new LinkedHashSet<>();
        List<String> previousTenantIds = usageMapper.findTenantIdsByDate(target);
        if (!CollectionUtils.isEmpty(previousTenantIds)) {
            previousTenantIds.stream().map(this::canonicalTenant).forEach(affectedTenants::add);
        }

        usageMapper.deleteByStatDate(target);
        for (UsageAggregate row : rows) {
            String tenantId = canonicalTenant(row.getTenantId());
            affectedTenants.add(tenantId);
            insert(row, tenantId, target, sourceMaxCallLogId);
        }
        verifySnapshot(rows, usageMapper.listByDate(null, target), target, sourceMaxCallLogId);

        if (!affectedTenants.isEmpty()) {
            eventPublisher.publishEvent(new UsageAggregationCompletedEvent(target, affectedTenants));
        }
        log.info("usage aggregation done, date={}, sourceMaxCallLogId={}, rows={}, tenants={}",
            target, sourceMaxCallLogId, rows.size(), affectedTenants.size());
        return rows.size();
    }

    private void insert(UsageAggregate row, String tenantId, LocalDate statDate,
                        long sourceMaxCallLogId) {
        CwTenantUsageDaily entity = new CwTenantUsageDaily();
        entity.setTenantId(tenantId);
        entity.setStatDate(statDate);
        entity.setProvider(normalize(row.getProvider()));
        entity.setModelName(normalize(row.getModelName()));
        entity.setCallCount(value(row.getCallCount()));
        entity.setInputTokens(value(row.getInputTokens()));
        entity.setOutputTokens(value(row.getOutputTokens()));
        entity.setCachedTokens(value(row.getCachedTokens()));
        entity.setTotalTokens(value(row.getTotalTokens()));
        entity.setModelSegmentCount(value(row.getModelSegmentCount()));
        entity.setSettledSegmentCount(value(row.getSettledSegmentCount()));
        entity.setUnsettledSegmentCount(value(row.getUnsettledSegmentCount()));
        entity.setAmount(amount(row.getAmount()));
        entity.setCurrency(normalize(row.getCurrency()));
        entity.setSourceMaxCallLogId(sourceMaxCallLogId);
        int inserted = TenantContext.callWith(tenantId, () -> usageMapper.insert(entity));
        if (inserted != 1) {
            throw new IllegalStateException("failed to persist usage aggregation row");
        }
    }

    /** 写后读取并逐字段核对，任何精度、分组或完整性漂移都让当前归集事务回滚。 */
    private void verifySnapshot(List<UsageAggregate> sourceRows,
                                List<UsageAggregate> persistedRows,
                                LocalDate statDate,
                                long sourceMaxCallLogId) {
        Map<UsageKey, UsageFingerprint> expected = fingerprints(sourceRows, sourceMaxCallLogId);
        Map<UsageKey, UsageFingerprint> actual = fingerprints(
            persistedRows == null ? List.of() : persistedRows, sourceMaxCallLogId);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("usage aggregation verification failed for date: " + statDate);
        }
    }

    private Map<UsageKey, UsageFingerprint> fingerprints(List<UsageAggregate> rows,
                                                          long fallbackSourceMaxId) {
        Map<UsageKey, UsageFingerprint> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (UsageAggregate row : rows) {
            UsageKey key = new UsageKey(canonicalTenant(row.getTenantId()), normalize(row.getProvider()),
                normalize(row.getModelName()), normalize(row.getCurrency()));
            long sourceMaxId = row.getSourceMaxCallLogId() == null
                ? fallbackSourceMaxId : row.getSourceMaxCallLogId();
            UsageFingerprint previous = result.put(key, new UsageFingerprint(
                value(row.getCallCount()), value(row.getInputTokens()), value(row.getOutputTokens()),
                value(row.getCachedTokens()), value(row.getTotalTokens()), value(row.getModelSegmentCount()),
                value(row.getSettledSegmentCount()), value(row.getUnsettledSegmentCount()),
                amount(row.getAmount()), sourceMaxId));
            if (previous != null) {
                throw new IllegalStateException("duplicate usage aggregation group: " + key);
            }
        }
        return result;
    }

    static SettlementWindow window(LocalDate date, ZoneId zone) {
        long fromMs = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long toMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new SettlementWindow(fromMs, toMs);
    }

    private BigDecimal amount(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        return normalized.setScale(ModelCallCost.AMOUNT_SCALE);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String canonicalTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank()
            ? TenantContext.DEFAULT : TenantContext.canonicalizeTenantId(tenantId);
    }

    record SettlementWindow(long fromMs, long toMs) {
    }

    private record UsageKey(String tenantId, String provider, String modelName, String currency) {
    }

    private record UsageFingerprint(long callCount, long inputTokens, long outputTokens,
                                    long cachedTokens, long totalTokens, long modelSegmentCount,
                                    long settledSegmentCount, long unsettledSegmentCount,
                                    BigDecimal amount, long sourceMaxCallLogId) {
    }
}
