package com.richard.fyoung.customerwork.safety.tenant;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 {@link TenantContext} 的 ThreadLocal 接入 Reactor 自动上下文传播。
 *
 * <p>WebFlux 请求链会在 boundedElastic 上执行阻塞 JDBC，线程一换 ThreadLocal 就没了。
 * 注册本 Accessor 并开启 {@code Hooks.enableAutomaticContextPropagation()} 后，
 * Reactor 会在每个线程边界把 Reactor Context 里的租户值还原进 ThreadLocal，
 * 使同步的 MyBatis 拦截器也能读到——这是"上下文只在入口写一次"能成立的前提。</p>
 * @author owlzhangfq@gmail.com
 */
public class TenantContextThreadLocalAccessor implements ThreadLocalAccessor<String> {

    /** Reactor Context 中承载租户的键，写入方（WebFilter）与本 Accessor 必须用同一个。 */
    public static final String KEY = "customer-work.tenant";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return TenantContext.get();
    }

    @Override
    public void setValue(String value) {
        TenantContext.set(value);
    }

    @Override
    public void setValue() {
        TenantContext.clear();
    }
}
