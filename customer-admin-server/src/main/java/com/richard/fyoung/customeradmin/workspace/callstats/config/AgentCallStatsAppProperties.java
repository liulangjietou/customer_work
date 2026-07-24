package com.richard.fyoung.customeradmin.workspace.callstats.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * APP 数据源（8080 客服端库 {@code agent_scope_customer_work}）连接参数：{@code admin.agent-call-stats.app.*}。
 *
 * <p>只用于"查询客服端调用日志"的第二只读数据源，默认指向本机 {@code localhost:3306} root/root。
 * 全部可经环境变量覆盖（见各字段默认值）。该库不可达不影响 admin 启动——数据源惰性构建、查询时才连。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "admin.agent-call-stats.app")
public class AgentCallStatsAppProperties {

    /** 数据库主机。 */
    private String host = "localhost";

    /** 数据库端口。 */
    private int port = 3306;

    /** 库名。 */
    private String database = "agent_scope_customer_work";

    /** 用户名。 */
    private String username = "root";

    /** 密码。 */
    private String password = "root";

    /** 拼装 JDBC URL（与 admin 主库同款连接参数）。 */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
