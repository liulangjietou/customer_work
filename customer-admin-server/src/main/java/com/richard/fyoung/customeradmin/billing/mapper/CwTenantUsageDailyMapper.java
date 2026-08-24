package com.richard.fyoung.customeradmin.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 租户日用量 Mapper。
 * @author owlzhangfq@gmail.com
 */
public interface CwTenantUsageDailyMapper extends BaseMapper<CwTenantUsageDaily> {

    /** 创建自然日串行锁记录；重复创建幂等。 */
    @InterceptorIgnore(tenantLine = "1")
    @Insert("INSERT IGNORE INTO cw_usage_aggregation_lock (stat_date) VALUES (#{statDate})")
    int ensureAggregationLock(@Param("statDate") LocalDate statDate);

    /** 在当前事务持有自然日行锁，避免多副本同时删除/重建同一天账单。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT stat_date FROM cw_usage_aggregation_lock WHERE stat_date = #{statDate} FOR UPDATE")
    LocalDate lockAggregationDate(@Param("statDate") LocalDate statDate);

    /** 读取重建前受影响租户，用于归集后预算事件。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("SELECT DISTINCT tenant_id FROM cw_tenant_usage_daily WHERE stat_date = #{statDate}")
    List<String> findTenantIdsByDate(@Param("statDate") LocalDate statDate);

    /** 串行锁内按自然日整体重建，确保已删除调用或迟到数据能被正确反映。 */
    @InterceptorIgnore(tenantLine = "1")
    @Delete("DELETE FROM cw_tenant_usage_daily WHERE stat_date = #{statDate}")
    int deleteByStatDate(@Param("statDate") LocalDate statDate);

    /** 读取某天账单事实；tenantId 为空仅供内部跨租户校验。 */
    @InterceptorIgnore(tenantLine = "1")
    List<UsageAggregate> listByDate(@Param("tenantId") String tenantId,
                                    @Param("statDate") LocalDate statDate);

    /** 按租户与日期区间汇总（账单报表用）。 */
    List<UsageAggregate> sumByTenantAndRange(@Param("tenantId") String tenantId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    /** 跨租户汇总（控制面账单总览）。 */
    List<UsageAggregate> sumGroupByTenant(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 指定租户、日期区间的 CNY 已结算金额，预算告警与预测共用同一账单口径。 */
    BigDecimal sumAmountByTenantAndRange(@Param("tenantId") String tenantId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
