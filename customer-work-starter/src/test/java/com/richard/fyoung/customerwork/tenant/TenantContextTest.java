package com.richard.fyoung.customerwork.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户上下文单测：持有、fail-closed 语义与嵌套作用域恢复。
 * @author owlzhangfq@gmail.com
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setAndGet_shouldRoundTrip() {
        TenantContext.set("acme");
        assertEquals("acme", TenantContext.get(), "应读回写入的租户");
        assertTrue(TenantContext.isPresent(), "写入后应判定为已设置");
    }

    @Test
    void set_shouldTreatBlankAsClear() {
        TenantContext.set("acme");
        TenantContext.set("  ");
        assertNull(TenantContext.get(), "空白值应视为清理，不能留下半初始化状态");
        assertFalse(TenantContext.isPresent(), "清理后应判定为未设置");
    }

    @Test
    void require_shouldThrow_whenAbsent() {
        assertThrows(TenantContextMissingException.class, TenantContext::require,
            "缺上下文必须抛出，绝不能放行成跨租户查询");
    }

    @Test
    void callWith_shouldRestorePreviousTenant() {
        TenantContext.set("outer");

        String inner = TenantContext.callWith("inner", TenantContext::require);

        assertEquals("inner", inner, "作用域内应生效指定租户");
        assertEquals("outer", TenantContext.get(), "作用域结束应恢复原租户，而不是清空");
    }

    @Test
    void callWith_shouldRestoreEmpty_whenNoPreviousTenant() {
        TenantContext.callWith("temp", () -> null);
        assertNull(TenantContext.get(), "原本无租户时，作用域结束应回到无租户");
    }

    @Test
    void callWith_shouldRestore_evenWhenActionThrows() {
        TenantContext.set("outer");

        assertThrows(IllegalStateException.class, () -> TenantContext.callWith("inner", () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals("outer", TenantContext.get(), "异常路径同样要恢复，否则上下文会永久错乱");
    }

    @Test
    void runWith_shouldExecuteInGivenTenant() {
        StringBuilder seen = new StringBuilder();
        TenantContext.runWith("job-tenant", () -> seen.append(TenantContext.require()));
        assertEquals("job-tenant", seen.toString(), "无请求上下文的任务应能显式指定租户");
    }
}
