package com.richard.fyoung.customerwork.infra.transaction;

import java.util.function.Supplier;

/**
 * customer-work 业务事务执行器：让领域服务不直接依赖 Spring 事务 API。
 *
 * <p>内存模式使用 {@link #DIRECT}；JDBC 模式由持久化配置提供基于同一数据源的事务实现。</p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface CustomerWorkTransactionExecutor {

    /** 无事务直通实现，供内存存储与离线测试使用。 */
    CustomerWorkTransactionExecutor DIRECT = Supplier::get;

    /** 实现层事务入口。 */
    Object executeUnchecked(Supplier<?> action);

    /** 在一个事务边界内执行并返回结果。 */
    @SuppressWarnings("unchecked")
    default <T> T execute(Supplier<T> action) {
        return (T) executeUnchecked(action);
    }
}
