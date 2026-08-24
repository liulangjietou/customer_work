package com.richard.fyoung.customeradmin.improvement.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客服库改进信号查询必须显式隔离租户，并只按精确发布 revision 统计曝光。 */
class ImprovementSignalMapperSqlTest {

    @Test
    void everyCrossDatabaseQuery_shouldBypassImplicitInterceptorAndCarryTenantPredicate()
        throws Exception {
        for (Method method : ImprovementSignalMapper.class.getDeclaredMethods()) {
            InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
            assertEquals("1", ignore.tenantLine(), method.getName());
            assertTrue(sql(method).contains("tenant_id = #{tenantId}"), method.getName());
        }
    }

    @Test
    void recurrenceAndExposureQueries_shouldUseStableSignalAndExactRevision() throws Exception {
        String badcase = sql(ImprovementSignalMapper.class.getMethod(
            "findBadcase", String.class, String.class));
        String exposure = sql(ImprovementSignalMapper.class.getMethod(
            "exposureCalls", String.class, String.class, long.class, long.class));

        assertTrue(badcase.contains("matched.signal_hash = source.signal_hash"));
        assertTrue(badcase.contains("source.tenant_id = #{tenantId}"));
        assertTrue(exposure.contains("runtime_revision = #{revision}"));
        assertTrue(exposure.contains("start_time >= #{startMs}"));
        assertTrue(exposure.contains("start_time < #{endMs}"));
    }

    private String sql(Method method) {
        return String.join(" ", Arrays.stream(method.getAnnotation(Select.class).value())
            .map(value -> value.replaceAll("\\s+", " ").trim())
            .toList());
    }
}
