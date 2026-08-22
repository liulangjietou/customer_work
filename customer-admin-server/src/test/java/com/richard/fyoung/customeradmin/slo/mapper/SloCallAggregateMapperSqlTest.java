package com.richard.fyoung.customeradmin.slo.mapper;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SloCallAggregateMapperSqlTest {

    @Test
    void aggregateScript_shouldAlwaysKeepExplicitTenantAndTimePredicates() throws Exception {
        String sql = boundSql(null);

        assertTrue(sql.contains("tenant_id = ?"));
        assertTrue(sql.contains("start_time >= ?"));
        assertTrue(sql.contains("start_time < ?"));
        assertFalse(sql.contains("agent_code = ?"));
    }

    @Test
    void aggregateScript_shouldApplyExactAgentPredicateWhenScoped() throws Exception {
        String sql = boundSql("support-agent");
        assertTrue(sql.contains("agent_code = ?"));
    }

    private String boundSql(String agentCode) throws Exception {
        Method method = SloCallAggregateMapper.class.getMethod("aggregate", String.class,
            String.class, long.class, long.class, long.class);
        String script = method.getAnnotation(Select.class).value()[0];
        SqlSource source = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "tenant-a");
        params.put("agentCode", agentCode);
        params.put("fromMs", 1L);
        params.put("toMs", 2L);
        params.put("latencyThresholdMs", 3000L);
        BoundSql boundSql = source.getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
