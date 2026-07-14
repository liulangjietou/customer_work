package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL 配置管理执行防护参数：目标多为生产库，查询超时与最大行数是硬性兜底，
 * 防止一条慢 SQL 或大结果集拖垮外部库/后台进程（安全边界要求）。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.sql-config")
public class AdminSqlConfigProperties {

    /** 单条查询超时（秒），底层 JdbcTemplate.setQueryTimeout 兜底。 */
    private int queryTimeoutSeconds = 30;

    /** 单次查询/导出最大返回行数，底层 JdbcTemplate.setMaxRows 兜底，同时作为导出上限。 */
    private int maxRows = 2000;
}
