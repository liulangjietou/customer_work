package com.richard.fyoung.customeradmin.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customerwork.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量归集：把 {@code cw_agent_call_log} 的原始调用记录按「租户 + 日期 + 模型」汇总进
 * {@code cw_tenant_usage_daily}，并按当日单价算好金额。
 *
 * <p><b>为什么要落一张汇总表而不是查询时实时聚合</b>：账单要能对得上——单价会调整，
 * 实时聚合会让历史账单随调价而变动；且原始日志量级远大于汇总，按月出账时全表扫不可接受。</p>
 *
 * <p>归集可重复执行（同一天再跑一次就覆盖），因此补数据只要重跑对应日期即可。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class UsageAggregationService {

    private final CwTenantUsageDailyMapper usageMapper;
    private final ModelPriceService priceService;

    public UsageAggregationService(CwTenantUsageDailyMapper usageMapper, ModelPriceService priceService) {
        this.usageMapper = usageMapper;
        this.priceService = priceService;
    }

    /**
     * 归集指定日期的用量。
     *
     * <p>整段跑在跨租户豁免下：归集本身就是要覆盖所有租户，
     * 若按当前上下文过滤，就只会汇总到某一个租户的数据。</p>
     *
     * @return 写入的记录数
     */
    @Transactional(rollbackFor = Exception.class)
    public int aggregate(LocalDate statDate) {
        LocalDate target = statDate == null ? LocalDate.now().minusDays(1) : statDate;
        List<UsageAggregate> rows = CrossTenantOperations.execute(
            () -> usageMapper.aggregateFromCallLog(target));
        if (rows.isEmpty()) {
            log.info("usage aggregation found no data, date={}", target);
            return 0;
        }

        // 按当日 23:59:59 取价：同一天内调价的情况按当日最终价结算，避免同一天出现两种价格
        LocalDateTime settleAt = target.atTime(23, 59, 59);
        int written = 0;
        for (UsageAggregate row : rows) {
            written += upsert(row, target, settleAt);
        }
        log.info("usage aggregation done, date={}, rows={}", target, written);
        return written;
    }

    private int upsert(UsageAggregate row, LocalDate statDate, LocalDateTime settleAt) {
        String tenantId = row.getTenantId() == null || row.getTenantId().isBlank()
            ? TenantContext.DEFAULT : row.getTenantId();
        String provider = row.getProvider() == null ? "" : row.getProvider();
        String modelName = row.getModelName() == null ? "" : row.getModelName();

        BigDecimal amount = priceService.calculate(provider, modelName,
            nullToZero(row.getInputTokens()), nullToZero(row.getOutputTokens()),
            nullToZero(row.getCachedTokens()), settleAt);

        // 归集结果本身跨租户，写入时要切到对应租户的上下文，让拦截器补出正确的 tenant_id
        return TenantContext.callWith(tenantId, () -> {
            CwTenantUsageDaily existing = usageMapper.selectOne(new LambdaQueryWrapper<CwTenantUsageDaily>()
                .eq(CwTenantUsageDaily::getStatDate, statDate)
                .eq(CwTenantUsageDaily::getProvider, provider)
                .eq(CwTenantUsageDaily::getModelName, modelName));

            CwTenantUsageDaily entity = existing == null ? new CwTenantUsageDaily() : existing;
            entity.setTenantId(tenantId);
            entity.setStatDate(statDate);
            entity.setProvider(provider);
            entity.setModelName(modelName);
            entity.setCallCount(nullToZero(row.getCallCount()));
            entity.setInputTokens(nullToZero(row.getInputTokens()));
            entity.setOutputTokens(nullToZero(row.getOutputTokens()));
            entity.setCachedTokens(nullToZero(row.getCachedTokens()));
            entity.setTotalTokens(nullToZero(row.getTotalTokens()));
            entity.setAmount(amount);
            entity.setCurrency("CNY");

            if (existing == null) {
                usageMapper.insert(entity);
            } else {
                usageMapper.updateById(entity);
            }
            return 1;
        });
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
