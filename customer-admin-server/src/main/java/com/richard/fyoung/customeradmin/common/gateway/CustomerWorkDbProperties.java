package com.richard.fyoung.customeradmin.common.gateway;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客服端库（{@code agent_scope_customer_work}）连接参数：{@code admin.customer-work-db.*}。
 *
 * <p><b>全 admin 唯一一份</b>。此前同一个物理库被三个属性类各配一份——
 * {@code admin.content-guard.*}、{@code admin.dict.*}、{@code admin.agent-call-stats.app.*}，
 * 五个连接字段默认值逐字相同、{@code jdbcUrl()} 三个方法体一模一样。
 * 它们不是"三个数据源"，是同一个库被写了三遍。</p>
 *
 * <p><b>为什么这件事比"少写两个类"重要</b>：没人愿意在 yml 里把同一个库的连接抄三遍，
 * 于是三份连接一个 {@code ${ENV}} 占位都没写、部署手册的"生产必配"表里一行都没有、
 * {@code deploy/k8s} 的 ConfigMap 里也没有——而 app-server 连同一个库用的是
 * {@code ${MYSQL_HOST}} 等占位，admin 自己的库用 {@code ${ADMIN_MYSQL_URL}}，两者都在清单里。
 * 按当时的清单部署，admin 容器里 12 个跨库门面会全部去连 pod 内的 {@code localhost:3306}。
 * 收敛成一份之后，它绑的就是 app-server 那套 {@code MYSQL_*} 变量，
 * 而 admin Deployment 已经 envFrom 了同一份 ConfigMap，无需新增任何部署配置。</p>
 *
 * <p>{@link CustomerWorkDbConnection} 接口保留：将来某个域确实要接读库副本时仍能自行实现。
 * 但"默认实现只有一个"由 {@code CustomerWorkDbSingleSourceTest} 守着——
 * 多一个同值副本就等于多一处会漂移的真相。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
@ConfigurationProperties(prefix = "admin.customer-work-db")
public class CustomerWorkDbProperties implements CustomerWorkDbConnection {

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

    /**
     * Admin 首次访问客服端库前是否执行其权威 Flyway。
     *
     * <p>生产 profile 显式关闭，沿用"客服端库结构由 DBA 手工变更、Admin 只消费已审核结构"的约定。
     * 此前这条铁律要靠三行 {@code schema-migration-enabled: false} 各写一遍来守，
     * 新增第四个跨库能力域时只要再造一份 properties 就会漏掉，
     * 而漏掉的后果是 admin 拿着自己那份连接对生产客服端库跑 DDL。</p>
     */
    private boolean schemaMigrationEnabled = true;

    /** 拼装 JDBC URL（与 admin 主库同款连接参数）。 */
    @Override
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }
}
