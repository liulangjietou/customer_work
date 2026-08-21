package com.richard.fyoung.customeradmin.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 租户日用量 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface CwTenantUsageDailyMapper extends BaseMapper<CwTenantUsageDaily> {

    /**
     * 从调用日志汇总某一天的用量（按租户 + 模型分组），供归集任务写入本表。
     *
     * <p>数据源是 {@code cw_agent_call_log}——那才是每次调用的原始记录，
     * 本表只是它的按日汇总视图。</p>
     */
    List<UsageAggregate> aggregateFromCallLog(@Param("statDate") LocalDate statDate);

    /** 按租户与日期区间汇总（账单报表用）。 */
    List<UsageAggregate> sumByTenantAndRange(@Param("tenantId") String tenantId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** 跨租户汇总（控制面账单总览）。 */
    List<UsageAggregate> sumGroupByTenant(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
