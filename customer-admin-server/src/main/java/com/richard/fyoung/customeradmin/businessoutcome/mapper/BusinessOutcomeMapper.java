package com.richard.fyoung.customeradmin.businessoutcome.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeAggregateRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 客服端业务结果只读模型。
 *
 * <p>所有基础表都显式限定 tenant_id；含 CTE 的查询关闭自动租户改写，避免拦截器把派生表误判为
 * 带 tenant_id 的物理表。观测窗口以调用日志 start_time 为准，结果事实按 session_id 关联。
 * 金额不在此查询：现有金额事实只有“租户+自然日+模型”粒度，无法无损下钻到会话。</p>
 */
public interface BusinessOutcomeMapper {

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        <script>
        WITH observed_calls AS (
            SELECT l.session_id,
                   GROUP_CONCAT(DISTINCT l.agent_code ORDER BY l.agent_code SEPARATOR ',') AS agent_codes,
                   MIN(l.start_time) AS first_call_at_ms,
                   MAX(l.start_time) AS last_call_at_ms,
                   COUNT(*) AS call_count,
                   SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) AS failed_calls,
                   SUM(CASE WHEN l.total_tokens IS NOT NULL THEN 1 ELSE 0 END) AS known_token_calls,
                   SUM(CASE WHEN l.total_tokens IS NULL THEN 1 ELSE 0 END) AS unknown_token_calls,
                   SUM(l.total_tokens) AS known_total_tokens
              FROM cw_agent_call_log l
             WHERE l.tenant_id = #{tenantId}
               AND l.start_time &gt;= #{fromMs}
               AND l.start_time &lt; #{toMs}
               AND l.session_id IS NOT NULL
               AND l.session_id &lt;&gt; ''
            <if test="agentCode != null and agentCode != ''">
               AND l.agent_code = #{agentCode}
            </if>
             GROUP BY l.session_id
        ),
        handoff_sessions AS (
            SELECT CAST(h.session_id AS BINARY) AS session_id
              FROM cw_handoff_ticket h
             WHERE h.tenant_id = #{tenantId}
               AND h.session_id IS NOT NULL
               AND h.session_id &lt;&gt; ''
            UNION
            SELECT CAST(t.session_id AS BINARY) AS session_id
              FROM cw_ticket t
             WHERE t.tenant_id = #{tenantId}
               AND t.session_id IS NOT NULL
               AND t.session_id &lt;&gt; ''
               AND (COALESCE(t.handoff_at_ms, 0) &gt; 0
                    OR NULLIF(TRIM(t.handoff_reason), '') IS NOT NULL)
        )
        SELECT COUNT(*) AS total_sessions,
               COALESCE(SUM(CASE WHEN o.failed_calls = 0 THEN 1 ELSE 0 END), 0) AS successful_sessions,
               COALESCE(SUM(CASE WHEN o.failed_calls = 0 AND h.session_id IS NULL THEN 1 ELSE 0 END), 0)
                   AS auto_resolved_proxy_sessions,
               COALESCE(SUM(CASE WHEN h.session_id IS NOT NULL THEN 1 ELSE 0 END), 0) AS handoff_sessions,
               COALESCE(SUM(o.call_count), 0) AS total_calls,
               COALESCE(SUM(o.known_token_calls), 0) AS known_token_calls,
               COALESCE(SUM(o.unknown_token_calls), 0) AS unknown_token_calls,
               SUM(o.known_total_tokens) AS known_total_tokens,
               COALESCE(SUM(CASE WHEN c.session_id IS NOT NULL THEN 1 ELSE 0 END), 0) AS csat_invited_sessions,
               COALESCE(SUM(CASE WHEN c.score IS NOT NULL THEN 1 ELSE 0 END), 0) AS csat_responded_sessions,
               COALESCE(SUM(CASE WHEN c.score &gt;= 4 THEN 1 ELSE 0 END), 0) AS csat_satisfied_sessions,
               AVG(c.score) AS average_csat
          FROM observed_calls o
          LEFT JOIN handoff_sessions h ON h.session_id = CAST(o.session_id AS BINARY)
          LEFT JOIN cw_csat_survey c ON c.tenant_id = #{tenantId}
                                    AND CAST(c.session_id AS BINARY) = CAST(o.session_id AS BINARY)
        </script>
        """)
    BusinessOutcomeAggregateRow aggregate(@Param("tenantId") String tenantId,
                                          @Param("agentCode") String agentCode,
                                          @Param("fromMs") long fromMs,
                                          @Param("toMs") long toMs);

    @Select("""
        <script>
        SELECT COUNT(DISTINCT l.session_id)
          FROM cw_agent_call_log l
         WHERE l.tenant_id = #{tenantId}
           AND l.start_time &gt;= #{fromMs}
           AND l.start_time &lt; #{toMs}
           AND l.session_id IS NOT NULL
           AND l.session_id &lt;&gt; ''
        <if test="agentCode != null and agentCode != ''">
           AND l.agent_code = #{agentCode}
        </if>
        </script>
        """)
    long countSessions(@Param("tenantId") String tenantId,
                       @Param("agentCode") String agentCode,
                       @Param("fromMs") long fromMs,
                       @Param("toMs") long toMs);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        <script>
        WITH observed_calls AS (
            SELECT l.session_id,
                   GROUP_CONCAT(DISTINCT l.agent_code ORDER BY l.agent_code SEPARATOR ',') AS agent_codes,
                   MIN(l.start_time) AS first_call_at_ms,
                   MAX(l.start_time) AS last_call_at_ms,
                   COUNT(*) AS call_count,
                   SUM(CASE WHEN l.success = 0 THEN 1 ELSE 0 END) AS failed_calls,
                   SUM(CASE WHEN l.total_tokens IS NOT NULL THEN 1 ELSE 0 END) AS known_token_calls,
                   SUM(CASE WHEN l.total_tokens IS NULL THEN 1 ELSE 0 END) AS unknown_token_calls,
                   SUM(l.total_tokens) AS known_total_tokens
              FROM cw_agent_call_log l
             WHERE l.tenant_id = #{tenantId}
               AND l.start_time &gt;= #{fromMs}
               AND l.start_time &lt; #{toMs}
               AND l.session_id IS NOT NULL
               AND l.session_id &lt;&gt; ''
            <if test="agentCode != null and agentCode != ''">
               AND l.agent_code = #{agentCode}
            </if>
             GROUP BY l.session_id
        ),
        handoff_sessions AS (
            SELECT CAST(h.session_id AS BINARY) AS session_id
              FROM cw_handoff_ticket h
             WHERE h.tenant_id = #{tenantId}
               AND h.session_id IS NOT NULL
               AND h.session_id &lt;&gt; ''
            UNION
            SELECT CAST(t.session_id AS BINARY) AS session_id
              FROM cw_ticket t
             WHERE t.tenant_id = #{tenantId}
               AND t.session_id IS NOT NULL
               AND t.session_id &lt;&gt; ''
               AND (COALESCE(t.handoff_at_ms, 0) &gt; 0
                    OR NULLIF(TRIM(t.handoff_reason), '') IS NOT NULL)
        )
        SELECT o.session_id,
               o.agent_codes,
               o.first_call_at_ms,
               o.last_call_at_ms,
               o.call_count,
               o.failed_calls,
               o.known_token_calls,
               o.unknown_token_calls,
               o.known_total_tokens,
               CASE WHEN h.session_id IS NULL THEN 0 ELSE 1 END AS handed_off,
               c.score AS csat_score
          FROM observed_calls o
          LEFT JOIN handoff_sessions h ON h.session_id = CAST(o.session_id AS BINARY)
          LEFT JOIN cw_csat_survey c ON c.tenant_id = #{tenantId}
                                    AND CAST(c.session_id AS BINARY) = CAST(o.session_id AS BINARY)
         ORDER BY o.last_call_at_ms DESC, o.session_id ASC
         LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<BusinessOutcomeSessionRow> findSessions(@Param("tenantId") String tenantId,
                                                 @Param("agentCode") String agentCode,
                                                 @Param("fromMs") long fromMs,
                                                 @Param("toMs") long toMs,
                                                 @Param("offset") long offset,
                                                 @Param("limit") int limit);
}
