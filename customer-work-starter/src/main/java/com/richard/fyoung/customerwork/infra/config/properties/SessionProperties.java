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
        /** 是否自动建库建表。 */
        private boolean autoCreate = true;

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
