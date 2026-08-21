package com.richard.fyoung.customerwork.safety.tenant;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户过滤策略单测：租户值来源、忽略表口径与跨租户豁免的嵌套安全。
 * @author owlzhangfq@gmail.com
 */
class TenantLineHandlerTest {

    private final CustomerWorkTenantLineHandler handler =
        new CustomerWorkTenantLineHandler("tenant_id", List.of("sys_permission", "ai_system_tool"));

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getTenantId_shouldReturnCurrentTenantAsStringValue() {
        TenantContext.set("acme");
        assertEquals("acme", ((StringValue) handler.getTenantId()).getValue(), "应取当前上下文的租户值");
    }

    @Test
    void getTenantId_shouldFailClosed_whenContextAbsent() {
        assertThrows(TenantContextMissingException.class, handler::getTenantId,
            "缺上下文时必须拒绝拼 SQL，而不是查出全量数据");
    }

    @Test
    void ignoreTable_shouldBeCaseInsensitive() {
        assertTrue(handler.ignoreTable("sys_permission"), "清单内的表应忽略过滤");
        assertTrue(handler.ignoreTable("SYS_PERMISSION"), "表名大小写不应影响判定");
        assertTrue(handler.ignoreTable("  ai_system_tool "), "两端空白不应影响判定");
        assertFalse(handler.ignoreTable("cw_ticket"), "业务表必须参与过滤");
        assertFalse(handler.ignoreTable(null), "表名为空时按不忽略处理，宁可报错也不放行");
    }

    @Test
    void build_shouldCoverPlatformLevelTables() {
        TenantLineInnerInterceptor interceptor = TenantInterceptors.build("tenant_id", List.of("host_own_table"));
        assertNotNull(interceptor, "应构建出可用拦截器");

        CustomerWorkTenantLineHandler built =
            new CustomerWorkTenantLineHandler("tenant_id", TenantInterceptors.TENANT_IGNORED_TABLES);
        assertTrue(built.ignoreTable("ai_chat_session_state"), "框架自建表必须在忽略清单里，否则会拼出不存在的列");
        assertTrue(built.ignoreTable("ai_model_config"), "两级可见的模型配置由 Service 层过滤，不走自动改写");
        assertTrue(built.ignoreTable("flyway_schema_history"), "迁移元数据表不属于任何租户");
    }

    @Test
    void crossTenantOperations_shouldToggleIgnoreFlag() {
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("any"), "默认不豁免");

        CrossTenantOperations.run(() ->
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine("any"), "作用域内应豁免租户过滤"));

        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("any"), "作用域结束应恢复过滤");
    }

    @Test
    void crossTenantOperations_shouldSurviveNesting() {
        CrossTenantOperations.run(() -> {
            CrossTenantOperations.run(() -> { });
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine("any"),
                "内层作用域结束不得清掉外层豁免——MyBatis-Plus 的 clear 是无条件 remove");
        });
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("any"), "最外层结束才恢复过滤");
    }

    @Test
    void crossTenantOperations_shouldRestore_evenWhenActionThrows() {
        assertThrows(IllegalStateException.class, () -> CrossTenantOperations.run(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("any"), "异常路径也必须恢复过滤");
    }
}
