package com.richard.fyoung.customeradmin.billing.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 客服端库模型调用金额事实的只读 Mapper。所有 SQL 都显式限定时间与可选租户。 */
public interface CustomerUsageFactMapper {

    /** 返回窗口内含 MODEL 分段的最大调用 ID，作为本次账单归集的一致性上界。 */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        <script>
        SELECT COALESCE(MAX(l.id), 0)
          FROM cw_agent_call_log l
         WHERE l.start_time &gt;= #{fromMs}
           AND l.start_time &lt; #{toMs}
           AND EXISTS (
               SELECT 1 FROM cw_agent_call_segment s
                WHERE s.call_log_id = l.id
                  AND s.tenant_id = l.tenant_id
                  AND s.kind = 'MODEL'
           )
        <if test="tenantId != null and tenantId != ''">
           AND l.tenant_id = #{tenantId}
        </if>
        </script>
        """)
    long maxCallLogId(@Param("tenantId") String tenantId,
                      @Param("fromMs") long fromMs,
                      @Param("toMs") long toMs);

    /**
     * 按租户、真实供应商、模型与币种汇总冻结上界内的 MODEL 分段。
     * 金额只求和已 SETTLED 的持久事实，不再按查询时价目二次计算。
     */
    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        <script>
        SELECT l.tenant_id AS tenantId,
               COALESCE(NULLIF(TRIM(s.provider), ''), '') AS provider,
               COALESCE(NULLIF(TRIM(s.model_name), ''), NULLIF(TRIM(s.name), ''), '') AS modelName,
               COALESCE(NULLIF(TRIM(s.cost_currency), ''), NULLIF(TRIM(s.currency), ''), '') AS currency,
               COUNT(DISTINCT l.id) AS callCount,
               COALESCE(SUM(s.input_tokens), 0) AS inputTokens,
               COALESCE(SUM(s.output_tokens), 0) AS outputTokens,
               COALESCE(SUM(s.cached_tokens), 0) AS cachedTokens,
               COALESCE(SUM(COALESCE(s.input_tokens, 0) + COALESCE(s.output_tokens, 0)), 0)
                   AS totalTokens,
               COUNT(*) AS modelSegmentCount,
               SUM(CASE WHEN s.cost_status = 'SETTLED' THEN 1 ELSE 0 END) AS settledSegmentCount,
               SUM(CASE WHEN s.cost_status &lt;&gt; 'SETTLED' THEN 1 ELSE 0 END) AS unsettledSegmentCount,
               CASE
                   WHEN SUM(CASE WHEN s.cost_status = 'SETTLED' THEN 1 ELSE 0 END) = COUNT(*)
                       THEN 'COMPLETE'
                   WHEN SUM(CASE WHEN s.cost_status = 'SETTLED' THEN 1 ELSE 0 END) = 0
                       THEN 'UNAVAILABLE'
                   ELSE 'PARTIAL'
               END AS pricingStatus,
               COALESCE(SUM(CASE WHEN s.cost_status = 'SETTLED' THEN s.cost_amount ELSE 0 END), 0)
                   AS amount
          FROM cw_agent_call_log l
          INNER JOIN cw_agent_call_segment s
                  ON s.call_log_id = l.id
                 AND s.tenant_id = l.tenant_id
                 AND s.kind = 'MODEL'
         WHERE l.start_time &gt;= #{fromMs}
           AND l.start_time &lt; #{toMs}
           AND l.id &lt;= #{maxCallLogId}
        <if test="tenantId != null and tenantId != ''">
           AND l.tenant_id = #{tenantId}
        </if>
         GROUP BY l.tenant_id,
                  COALESCE(NULLIF(TRIM(s.provider), ''), ''),
                  COALESCE(NULLIF(TRIM(s.model_name), ''), NULLIF(TRIM(s.name), ''), ''),
                  COALESCE(NULLIF(TRIM(s.cost_currency), ''), NULLIF(TRIM(s.currency), ''), '')
         ORDER BY l.tenant_id, provider, modelName, currency
        </script>
        """)
    List<UsageAggregate> aggregate(@Param("tenantId") String tenantId,
                                   @Param("fromMs") long fromMs,
                                   @Param("toMs") long toMs,
                                   @Param("maxCallLogId") long maxCallLogId);
}
