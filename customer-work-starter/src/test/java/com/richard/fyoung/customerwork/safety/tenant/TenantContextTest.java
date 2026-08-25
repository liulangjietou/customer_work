package com.richard.fyoung.customerwork.safety.tenant;

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

    /**
     * 传播恢复路径与 {@code set} 对合法值必须完全等价——包括 default 大小写归一。
     * 不等价就意味着"值穿过一次线程边界后变了"，那是最难查的一类隔离缺陷。
     */
    @Test
    void restore_shouldMatchSetForLegalValues() {
        TenantContext.restore("acme");
        assertEquals("acme", TenantContext.get(), "合法值应原样恢复");

        TenantContext.restore("DEFAULT");
        assertEquals(TenantContext.DEFAULT, TenantContext.get(), "恢复路径同样要归一保留租户");
    }

    /** 空值语义与 {@code set} 一致：视为清理，不留半初始化状态。 */
    @Test
    void restore_shouldClearOnBlank() {
        TenantContext.set("acme");

        TenantContext.restore("  ");

        assertNull(TenantContext.get(), "空白值应清理而非写入");
        assertFalse(TenantContext.isPresent());
    }

    /**
     * 恢复路径<b>刻意</b>不做格式校验，这正是它与 {@code set} 的唯一区别，也是它存在的理由：
     * 自动上下文传播会给 Reactor 链上每个算子包一层，AI 流式链实测 110+ 层，模型每吐一个增量
     * 就要走上百次；在这里跑正则曾把一整颗核烧满，把流式吞吐压到约 5 字符/秒。
     * 校验仍由所有入口的 {@code set} 承担。
     */
    @Test
    void restore_shouldSkipFormatValidation() {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.set("bad tenant!"),
            "入口写入必须继续 fail fast");

        TenantContext.restore("bad tenant!");
        assertEquals("bad tenant!", TenantContext.get(), "恢复路径不重复校验，原样放回");
    }

    /**
     * 防回归：Accessor 必须走 {@code restore} 而不是 {@code set}。
     * 改回 {@code set} 会让上百层传播重新跑正则，卡顿当场复发，而功能测试一条都不会红——
     * 这个断言是那次回归唯一的机器防线。
     */
    @Test
    void accessorSetValue_shouldUseRestorePath() {
        TenantContextThreadLocalAccessor accessor = new TenantContextThreadLocalAccessor();

        accessor.setValue("bad tenant!");

        assertEquals("bad tenant!", TenantContext.get(),
            "Accessor 恢复快照值不得触发格式校验（否则即为改回了 set）");
    }

    @Test
    void setAndGet_shouldRoundTrip() {
        TenantContext.set("acme");
        assertEquals("acme", TenantContext.get(), "应读回写入的租户");
        assertTrue(TenantContext.isPresent(), "写入后应判定为已设置");
    }

    @Test
    void set_shouldCanonicalizeOnlyDefaultCaseAlias() {
        TenantContext.set("DEFAULT");

        assertEquals(TenantContext.DEFAULT, TenantContext.get());
        assertTrue(TenantContext.isDefaultTenant("Default"));
        assertTrue(TenantContext.sameTenant("Tenant-A", "tenant-a"));
        assertEquals("tenant-a", TenantContext.normalizedTenantKey("Tenant-A"));
        assertFalse(TenantContext.isDefaultTenant("default-tenant"));
    }

    @Test
    void set_shouldTreatBlankAsClear() {
        TenantContext.set("acme");
        TenantContext.set("  ");
        assertNull(TenantContext.get(), "空白值应视为清理，不能留下半初始化状态");
        assertFalse(TenantContext.isPresent(), "清理后应判定为未设置");
    }

    @Test
    void set_shouldRejectTenantIdOutsideCreationContract() {
        assertThrows(IllegalArgumentException.class, () -> TenantContext.set("_legacy"));
        assertThrows(IllegalArgumentException.class, () -> TenantContext.set("tenant/escape"));
        assertFalse(TenantContext.isPresent(), "非法租户不能污染线程上下文");
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
