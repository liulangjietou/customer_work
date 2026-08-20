package com.richard.fyoung.customerwork.infra.gateway;

/**
 * 跨库门面的连接参数：一个独立连接池所需的全部输入。
 *
 * <p>下游（如后台管理端读写客服端库）各自的 {@code @ConfigurationProperties} 只负责"从配置读出主机/库名/账号"，
 * 拼出 JDBC URL 后转成本对象交给 {@link CrossDbGateways}——配置前缀与字段留在各自模块，池化策略统一在此。</p>
 *
 * <p>默认值取自既有三处跨库门面的公共形态：驱动 MySQL、{@code minimumIdle=0}（闲时不占连接）、
 * 连接超时 5s（库不可达要快速失败，不能把请求线程吊死）、池上限 3。只读源把 {@code readOnly} 打开。</p>
 * @author owlzhangfq@gmail.com
 */
public record CrossDbConnectionSettings(String poolName,
                                        String jdbcUrl,
                                        String username,
                                        String password,
                                        String driverClassName,
                                        int maximumPoolSize,
                                        int minimumIdle,
                                        long connectionTimeoutMs,
                                        boolean readOnly) {

    /** 默认驱动：跨库门面目前一律指向 MySQL 客服端库。 */
    public static final String DEFAULT_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    /** 默认池上限（跨库访问是旁路读写，不该跟主库抢连接）。 */
    /** 跨库门面的连接池默认上限：门面只做低频运维查询，池子开大反而占着客服端库的连接配额。 */
    public static final int DEFAULT_MAX_POOL_SIZE = 3;

    /** 默认连接超时：库不可达时 5s 内失败返回。 */
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5000L;

    public CrossDbConnectionSettings {
        if (poolName == null || poolName.trim().isEmpty()) {
            throw new IllegalArgumentException("poolName must not be blank");
        }
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
    }

    /** 起一个构建器：池名与 JDBC URL 是必填项，其余走默认值。 */
    public static Builder builder(String poolName, String jdbcUrl) {
        return new Builder(poolName, jdbcUrl);
    }

    /** {@link CrossDbConnectionSettings} 构建器（字段多且多数取默认，逐个具名赋值比长参数列表可读）。 */
    public static final class Builder {

        private final String poolName;
        private final String jdbcUrl;
        private String username;
        private String password;
        private String driverClassName = DEFAULT_DRIVER_CLASS;
        private int maximumPoolSize = DEFAULT_MAX_POOL_SIZE;
        private int minimumIdle = 0;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private boolean readOnly = false;

        private Builder(String poolName, String jdbcUrl) {
            this.poolName = poolName;
            this.jdbcUrl = jdbcUrl;
        }

        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder driverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /** 只读源打开后 Hikari 会把连接置为只读，误写立即报错（比事后查数据被改快得多）。 */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public CrossDbConnectionSettings build() {
            return new CrossDbConnectionSettings(poolName, jdbcUrl, username, password, driverClassName,
                maximumPoolSize, minimumIdle, connectionTimeoutMs, readOnly);
        }
    }
}
