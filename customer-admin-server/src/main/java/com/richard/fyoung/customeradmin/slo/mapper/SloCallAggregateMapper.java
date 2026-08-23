package com.richard.fyoung.customeradmin.slo.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** SLO 读模型。所有 SQL 都显式携带 tenant_id，避免依赖线程租户插件的隐式行为。 */
public interface SloCallAggregateMapper {

    @Select("""
        <script>
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS availability_good,
               COALESCE(SUM(CASE WHEN duration_ms &lt;= #{latencyThresholdMs} THEN 1 ELSE 0 END), 0) AS latency_good,
               COALESCE(SUM(CASE WHEN success = 1 AND duration_ms &lt;= #{latencyThresholdMs}
                                 THEN 1 ELSE 0 END), 0) AS composite_good
          FROM cw_agent_call_log
         WHERE tenant_id = #{tenantId}
           AND start_time &gt;= #{fromMs}
           AND start_time &lt; #{toMs}
        <if test="agentCode != null and agentCode != ''">
           AND agent_code = #{agentCode}
        </if>
        </script>
        """)
    SloCallAggregate aggregate(@Param("tenantId") String tenantId,
                               @Param("agentCode") String agentCode,
                               @Param("fromMs") long fromMs,
                               @Param("toMs") long toMs,
                               @Param("latencyThresholdMs") long latencyThresholdMs);

    @Select("""
        SELECT a.agent_code
          FROM ai_channel_binding b
          JOIN ai_agent a ON a.id = b.agent_id
                         AND a.tenant_id = #{tenantId}
                         AND a.deleted = 0
         WHERE b.tenant_id = #{tenantId}
           AND b.channel_code = #{channelCode}
           AND b.status = 1
           AND b.deleted = 0
         LIMIT 1
        """)
    String findAgentCodeByChannel(@Param("tenantId") String tenantId,
                                  @Param("channelCode") String channelCode);
}
