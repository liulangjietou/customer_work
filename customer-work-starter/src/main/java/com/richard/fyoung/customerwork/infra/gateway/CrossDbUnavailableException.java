package com.richard.fyoung.customerwork.infra.gateway;

/**
 * 跨库门面所指向的数据库不可达（连接池建连/探测失败）。
 *
 * <p>与"门面装配失败"（Mapper XML 写错这类代码问题，抛 {@link IllegalStateException}）刻意分开：
 * 前者是环境问题、可自愈、调用方应转成"依赖库暂不可用"的业务提示；后者是 bug，不该被当成可重试的故障。</p>
 * @author owlzhangfq@gmail.com
 */
public class CrossDbUnavailableException extends RuntimeException {

    private final String poolName;
    private final String jdbcUrl;

    public CrossDbUnavailableException(String poolName, String jdbcUrl, Throwable cause) {
        super("cross-db datasource unavailable, pool=" + poolName + ", url=" + jdbcUrl, cause);
        this.poolName = poolName;
        this.jdbcUrl = jdbcUrl;
    }

    public String getPoolName() {
        return poolName;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * 取最内层原因的可读信息，供调用方拼进给用户看的提示。
     *
     * <p>外层包装（Hikari/驱动）说的是"取连接失败"，真正有用的是最内层那句
     * （如 "Connection refused"、"Access denied for user"）。</p>
     */
    public String rootMessage() {
        Throwable cause = this;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
