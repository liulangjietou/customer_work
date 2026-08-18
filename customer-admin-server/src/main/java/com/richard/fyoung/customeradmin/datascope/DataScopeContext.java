package com.richard.fyoung.customeradmin.datascope;

/**
 * 当前请求的数据范围上下文，供持久层拦截器读取。
 *
 * <p>admin 是 Spring MVC，一个请求一个线程，ThreadLocal 直接可用；线程复用，
 * 清理不能省，否则下一个请求会沿用上一个人的归属条件。与 {@code TenantContextInterceptor} 同构。</p>
 *
 * <p><b>没有上下文时不过滤，这是刻意的</b>：与租户维度的 fail-closed 相反。租户缺失意味着
 * "不知道该看哪个租户的数据"，放行等于泄露；而数据范围缺失只出现在压根没有"人"的链路上——
 * 内置调度器、异步回调、脚本令牌回调、开放 API。这些链路本就不是"页面数据"，
 * 对它们 fail-closed 只会把后台任务整体打挂，而跨租户的那道防线仍由租户拦截器守着。</p>
 * @author owlzhangfq@gmail.com
 */
public final class DataScopeContext {

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /** 上下文载荷：范围 + 归属人。 */
    public record Holder(DataScope scope, Long userId) {
    }

    /** 写入当前请求的范围与用户；{@code userId} 为空时等同于不限制。 */
    public static void set(DataScope scope, Long userId) {
        HOLDER.set(new Holder(scope, userId));
    }

    /** 当前范围；未设置返回 {@code null}（不过滤）。 */
    public static DataScope currentScope() {
        Holder holder = HOLDER.get();
        return holder == null ? null : holder.scope();
    }

    /**
     * 需要按其过滤的归属人 ID；仅 {@link DataScope#SELF} 时返回值，其余一律 {@code null}。
     *
     * <p>把"要不要过滤"和"按谁过滤"收敛到这一个方法，拦截器只需判空，
     * 不必在 SQL 改写处再重复一遍范围语义。</p>
     */
    public static Long restrictedUserId() {
        Holder holder = HOLDER.get();
        if (holder == null || holder.scope() != DataScope.SELF) {
            return null;
        }
        return holder.userId();
    }

    /**
     * 当前是否<b>明确</b>处于"本租户及以上"的范围（{@link DataScope#TENANT} / {@link DataScope#ALL}）。
     *
     * <p>与 {@link #restrictedUserId()} 的空值判断不是一回事，两者不能互相替代：
     * 那个方法返回 {@code null} 既可能是范围放宽了，也可能是压根没有上下文。
     * 对"要不要额外加一道限制"（拦截器）来说，两种情形都按不加处理是安全的；
     * 但对"要不要放宽一道既有的限制"（会话归属校验）来说，缺上下文绝不能等同于放行——
     * 放宽必须有明确依据，否则任何脱离登录态的调用都能读到别人的会话。</p>
     */
    public static boolean relaxedBeyondSelf() {
        Holder holder = HOLDER.get();
        return holder != null && holder.scope() != null && holder.scope() != DataScope.SELF;
    }

    /**
     * 以指定用户的身份执行一段查询，结束后还原原上下文。
     *
     * <p>用于凭令牌而非登录态进入的链路：脚本回调拿着个人访问令牌，令牌背后是一个确切的人，
     * 因此不能按"无上下文即不过滤"处理——那会让 A 的脚本读到 B 录的站点密码。</p>
     */
    public static <T> T callAs(Long userId, java.util.function.Supplier<T> action) {
        Holder previous = HOLDER.get();
        HOLDER.set(new Holder(DataScope.SELF, userId));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /** 清理，必须在请求结束时调用。 */
    public static void clear() {
        HOLDER.remove();
    }
}
