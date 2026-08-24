package com.richard.fyoung.customeradmin.billing.mapper;

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

class CustomerUsageFactMapperSqlTest {

    @Test
    void aggregate_shouldUsePersistedSettlementAndFrozenCallBoundary() throws Exception {
        Method method = CustomerUsageFactMapper.class.getMethod("aggregate", String.class,
            long.class, long.class, long.class);
        String sql = boundSql(method, "tenant-a");

        assertTrue(sql.contains("s.cost_status = 'SETTLED'"));
        assertTrue(sql.contains("s.cost_amount"));
        assertTrue(sql.contains("l.id <= ?"));
        assertTrue(sql.contains("l.tenant_id = ?"));
        assertTrue(sql.contains("s.tenant_id = l.tenant_id"));
        assertFalse(sql.contains("ai_model_price"));
        assertFalse(sql.contains("input_unit_price *"));
    }

    @Test
    void aggregate_shouldAllowOnlyInternalAllTenantModeWhenTenantIsAbsent() throws Exception {
        Method method = CustomerUsageFactMapper.class.getMethod("aggregate", String.class,
            long.class, long.class, long.class);
        assertFalse(boundSql(method, null).contains("l.tenant_id = ?"));
    }

    private String boundSql(Method method, String tenantId) {
        String script = method.getAnnotation(Select.class).value()[0];
        SqlSource source = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("fromMs", 1L);
        params.put("toMs", 2L);
        params.put("maxCallLogId", 9L);
        BoundSql boundSql = source.getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
