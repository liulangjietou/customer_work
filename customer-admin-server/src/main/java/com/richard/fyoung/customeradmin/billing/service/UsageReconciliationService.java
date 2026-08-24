package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.config.BillingSettlementProperties;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.dto.UsageReconciliationVO;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGatewayProvider;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallCost;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将客服端当前调用金额事实与已归集日账单逐日核对。 */
@Service
public class UsageReconciliationService {

    private final CwTenantUsageDailyMapper usageMapper;
    private final CustomerUsageFactGatewayProvider sourceProvider;
    private final BillingSettlementProperties properties;

    public UsageReconciliationService(CwTenantUsageDailyMapper usageMapper,
                                      CustomerUsageFactGatewayProvider sourceProvider,
                                      BillingSettlementProperties properties) {
        this.usageMapper = usageMapper;
        this.sourceProvider = sourceProvider;
        this.properties = properties;
    }

    public List<UsageReconciliationVO> reconcile(String tenantId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<UsageReconciliationVO> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.addAll(reconcileDate(tenantId, date));
        }
        return result;
    }

    private List<UsageReconciliationVO> reconcileDate(String tenantId, LocalDate statDate) {
        UsageAggregationService.SettlementWindow window =
            UsageAggregationService.window(statDate, properties.zone());
        long sourceMaxId = CrossTenantOperations.execute(() -> sourceProvider.get().mapper()
            .maxCallLogId(tenantId, window.fromMs(), window.toMs()));
        List<UsageAggregate> source = sourceMaxId == 0L ? List.of()
            : CrossTenantOperations.execute(() -> sourceProvider.get().mapper()
                .aggregate(tenantId, window.fromMs(), window.toMs(), sourceMaxId));
        List<UsageAggregate> bill = CrossTenantOperations.execute(
            () -> usageMapper.listByDate(tenantId, statDate));

        Map<String, CostFact> sourceByCurrency = summarize(source, sourceMaxId);
        Map<String, CostFact> billByCurrency = summarize(bill, 0L);
        Set<String> currencies = new LinkedHashSet<>(sourceByCurrency.keySet());
        currencies.addAll(billByCurrency.keySet());
        List<UsageReconciliationVO> rows = new ArrayList<>();
        for (String currency : currencies) {
            CostFact sourceFact = sourceByCurrency.getOrDefault(currency, CostFact.empty(sourceMaxId));
            CostFact billFact = billByCurrency.getOrDefault(currency, CostFact.empty(0L));
            rows.add(toVO(tenantId, statDate, currency, sourceFact, billFact));
        }
        return rows;
    }

    private UsageReconciliationVO toVO(String tenantId, LocalDate statDate, String currency,
                                       CostFact source, CostFact bill) {
        boolean factsMatch = source.modelSegments() == bill.modelSegments()
            && source.settledSegments() == bill.settledSegments()
            && source.unsettledSegments() == bill.unsettledSegments()
            && source.amount().compareTo(bill.amount()) == 0
            && bill.snapshotConsistent();
        String status;
        String reason;
        if (!factsMatch && source.sourceMaxCallLogId() > bill.sourceMaxCallLogId()) {
            status = "STALE";
            reason = "客服端出现晚到或新增调用，请重新归集该自然日";
        } else if (!factsMatch) {
            status = "MISMATCH";
            reason = bill.snapshotConsistent()
                ? "调用金额事实与日账单不一致" : "同一天账单行的调用快照上界不一致";
        } else if (source.unsettledSegments() > 0L) {
            status = "INCOMPLETE";
            reason = "账实一致，但仍有缺价、缺 usage 或非法 usage 的模型分段";
        } else {
            status = "MATCHED";
            reason = "调用金额、分段完整性与日账单一致";
        }
        return new UsageReconciliationVO(tenantId, statDate,
            currency.isEmpty() ? "UNSPECIFIED" : currency,
            source.amount(), bill.amount(), source.amount().subtract(bill.amount()),
            source.modelSegments(), bill.modelSegments(),
            source.settledSegments(), bill.settledSegments(),
            source.unsettledSegments(), bill.unsettledSegments(),
            source.sourceMaxCallLogId(), bill.sourceMaxCallLogId(), status, reason);
    }

    private Map<String, CostFact> summarize(List<UsageAggregate> rows, long fallbackSourceMaxId) {
        Map<String, MutableCostFact> mutable = new LinkedHashMap<>();
        if (rows != null) {
            for (UsageAggregate row : rows) {
                String currency = row.getCurrency() == null ? "" : row.getCurrency().trim();
                MutableCostFact target = mutable.computeIfAbsent(currency, ignored -> new MutableCostFact());
                target.amount = target.amount.add(amount(row.getAmount()));
                target.modelSegments += value(row.getModelSegmentCount());
                target.settledSegments += value(row.getSettledSegmentCount());
                target.unsettledSegments += value(row.getUnsettledSegmentCount());
                target.snapshotIds.add(row.getSourceMaxCallLogId() == null
                    ? fallbackSourceMaxId : row.getSourceMaxCallLogId());
            }
        }
        Map<String, CostFact> result = new LinkedHashMap<>();
        mutable.forEach((currency, fact) -> result.put(currency, fact.freeze(fallbackSourceMaxId)));
        return result;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BizException(ResultCode.PARAM_INVALID, "对账日期范围不合法");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        int maximum = Math.max(1, properties.getMaxReconciliationDays());
        if (days > maximum) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "单次对账不能超过 " + maximum + " 个自然日");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(ModelCallCost.AMOUNT_SCALE);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private static final class MutableCostFact {
        private BigDecimal amount = BigDecimal.ZERO.setScale(ModelCallCost.AMOUNT_SCALE);
        private long modelSegments;
        private long settledSegments;
        private long unsettledSegments;
        private final Set<Long> snapshotIds = new LinkedHashSet<>();

        private CostFact freeze(long fallbackSourceMaxId) {
            long sourceMaxId = snapshotIds.isEmpty() ? fallbackSourceMaxId
                : snapshotIds.stream().mapToLong(Long::longValue).max().orElse(fallbackSourceMaxId);
            return new CostFact(amount, modelSegments, settledSegments, unsettledSegments,
                sourceMaxId, snapshotIds.size() <= 1);
        }
    }

    private record CostFact(BigDecimal amount, long modelSegments, long settledSegments,
                            long unsettledSegments, long sourceMaxCallLogId,
                            boolean snapshotConsistent) {
        private static CostFact empty(long sourceMaxCallLogId) {
            return new CostFact(BigDecimal.ZERO.setScale(ModelCallCost.AMOUNT_SCALE),
                0L, 0L, 0L, sourceMaxCallLogId, true);
        }
    }
}
