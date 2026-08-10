package com.richard.fyoung.customerwork.tenant;

import java.util.function.Supplier;

/**
 * 当前请求的租户上下文（全链路唯一真源）。
 *
 * <p>由接入层（API Key 鉴权 / H5 登录态 / admin 登录用户）在入口写入，持久层
 * {@link CustomerWorkTenantLineHandler} 在拼 SQL 时读取。中间的业务代码不感知租户——
 * 这正是把隔离做在拦截器而非各 Store 里的意义。</p>
 *
 * <p><b>为什么是 ThreadLocal 而不是 Reactor Context</b>：MyBatis 拦截器是同步 API，
 * 拿不到 Reactor Context。反过来让 WebFlux 侧把值同步到 ThreadLocal 才是可行方向，
 * 跨线程边界由 {@link TenantContextThreadLocalAccessor} + Reactor 自动传播兜住。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class TenantContext {

    /** 平台运营方自身的租户 ID（运营方后台用户、平台共享模型配置挂在此租户下）。 */
    public static final String PLATFORM = "__platform__";

    /** 升级前存量数据归属的默认租户，等价于"原来那个单租户系统"。 */
    public static final String DEFAULT = "default";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 写入当前租户；传空视为清理，避免半初始化状态被后续 SQL 读到。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(tenantId);
    }

    /** 读取当前租户，未设置返回 {@code null}（判空由调用方按场景决定 fail-open 还是 fail-closed）。 */
    public static String get() {
        return CURRENT.get();
    }

    /** 读取当前租户，未设置直接抛出——持久层等安全边界用这个，绝不允许"没租户也查得到数据"。 */
    public static String require() {
        String tenantId = CURRENT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    /** 是否已设置租户。 */
    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在指定租户下执行一段逻辑，结束后恢复原值。
     *
     * <p>给三类没有请求上下文的场景用：定时任务、启动期初始化、运营方切换目标租户后的跨租户操作。
     * 恢复而非清理，是因为调用可能嵌套（运营方在自己上下文里临时进入某租户）。</p>
     */
    public static <T> T callWith(String tenantId, Supplier<T> action) {
        String previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /** {@link #callWith(String, Supplier)} 的无返回值版本。 */
    public static void runWith(String tenantId, Runnable action) {
        callWith(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
