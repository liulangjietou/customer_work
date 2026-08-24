package com.richard.fyoung.customeradmin.businessoutcome.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessOutcomeMapperSqlTest {

    @Test
    void aggregate_shouldUseExplicitTenantWindowAndOnlyProvableSessionFacts() throws Exception {
        String sql = boundAggregate(null);

        assertTrue(sql.contains("l.tenant_id = ?"));
        assertTrue(sql.contains("t.tenant_id = ?"));
        assertTrue(sql.contains("c.tenant_id = ?"));
        assertTrue(sql.contains("l.start_time >= ?"));
        assertTrue(sql.contains("l.start_time < ?"));
        assertFalse(sql.contains("cw_handoff_ticket"));
        assertTrue(sql.contains("cw_ticket"));
        assertTrue(sql.contains("cw_csat_survey"));
        assertTrue(sql.contains("l.model_cost_amount"));
        assertTrue(sql.contains("l.model_cost_status = 'MULTI_CURRENCY'"));
        assertTrue(sql.contains("settled_cost_segment_count"));
        assertFalse(sql.contains("l.agent_code = ?"));
        assertFalse(sql.contains("cw_tenant_usage_daily"));
    }

    @Test
    void aggregate_shouldApplyExactAgentFilterWhenRequested() throws Exception {
        assertTrue(boundAggregate("support-agent").contains("l.agent_code = ?"));
    }

    @Test
    void count_shouldUseDistinctNonEmptySessionWithinTenantWindow() throws Exception {
        Method method = BusinessOutcomeMapper.class.getMethod("countSessions", String.class,
            String.class, long.class, long.class);
        String sql = boundSql(method, null);
        assertTrue(sql.contains("COUNT(DISTINCT l.session_id)"));
        assertTrue(sql.contains("l.tenant_id = ?"));
        assertTrue(sql.contains("l.start_time >= ?"));
        assertTrue(sql.contains("l.start_time < ?"));
    }

    @Test
    void sessionDrilldown_shouldUseDeterministicPagination() throws Exception {
        Method method = BusinessOutcomeMapper.class.getMethod("findSessions", String.class,
            String.class, long.class, long.class, long.class, int.class);
        String sql = boundSql(method, "support-agent");
        assertTrue(sql.contains("ORDER BY o.last_call_at_ms DESC, o.session_id ASC"));
        assertTrue(sql.contains("LIMIT ? OFFSET ?"));
        assertTrue(sql.contains("c.tenant_id = ?"));
    }

    private String boundAggregate(String agentCode) throws Exception {
        Method method = BusinessOutcomeMapper.class.getMethod("aggregate", String.class,
            String.class, long.class, long.class);
        return boundSql(method, agentCode);
    }

    private String boundSql(Method method, String agentCode) {
        String script = method.getAnnotation(Select.class).value()[0];
        SqlSource source = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "tenant-a");
        params.put("agentCode", agentCode);
        params.put("fromMs", 1L);
        params.put("toMs", 2L);
        params.put("offset", 0L);
        params.put("limit", 20);
        BoundSql boundSql = source.getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
