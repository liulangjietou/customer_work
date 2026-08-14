package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话持久化配置：memory | redis | mysql（不提供文件落盘形态）。 */
@Data
public class SessionProperties {
    private String mode = "memory";
    /** 会话空闲超时（分钟）：超过该时间无活动的会话自动清理；<=0 禁用。 */
    private int idleTimeoutMinutes = 0;
    /** Redis 连接配置（mode=redis 时生效）。 */
    private final Redis redis = new Redis();
    /** MySQL 连接配置（mode=mysql 时生效）。 */
    private final Mysql mysql = new Mysql();

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private String keyPrefix = "customer-work";
    }

    @Data
    public static class Mysql {
        private String host = "localhost";
        private int port = 3306;
        private String database = "agent_scope_customer_work";
        private String username = "root";
        private String password = "root";
        /** 完整 JDBC URL（留空则按 host/port/database 自动拼装）。 */
        private String jdbcUrl = "";
        /**
         * 是否自动执行数据库初始化。兼容旧配置；新部署请使用 {@link #migrationEnabled}。
         * 当 migration-enabled 未显式配置时，本字段仍作为 Flyway 迁移开关。
         */
        private boolean autoCreate = true;
        /** 是否执行 Flyway 版本化迁移；null 表示沿用 auto-create，避免旧部署升级后语义突变。 */
        private Boolean migrationEnabled;

        /** 返回兼容新旧配置后的最终迁移开关。 */
        public boolean isSchemaMigrationEnabled() {
            return migrationEnabled != null ? migrationEnabled : autoCreate;
        }

        /** 解析最终使用的 JDBC URL。 */
        public String resolveJdbcUrl() {
            if (jdbcUrl != null && !jdbcUrl.isBlank()) {
                return jdbcUrl;
            }
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&characterEncoding=UTF-8";
        }
    }
}
