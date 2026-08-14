package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.infra.migration.V2__ReconcileLegacySchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;

/**
 * 客服端独立业务库迁移入口。
 *
 * <p>迁移失败必须阻断依赖 JDBC Store 的应用启动。此前初始化器吞掉异常后继续启动，会把结构问题延迟到
 * 第一笔真实请求，既不利于回滚，也可能造成部分写入。需要纯内存运行时可显式关闭
 * {@code customer-work.session.mysql.migration-enabled}。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class CustomerWorkSchemaMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CustomerWorkSchemaMigrator.class);

    private static final String FLYWAY_LOCATIONS = "classpath:db/customerwork/migration";

    private final DataSource dataSource;
    private final boolean enabled;

    public CustomerWorkSchemaMigrator(DataSource dataSource, CustomerWorkProperties properties) {
        this.dataSource = dataSource;
        this.enabled = properties.getSession().getMysql().isSchemaMigrationEnabled();
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("customer-work schema migration disabled, skip");
            return;
        }
        try {
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(FLYWAY_LOCATIONS)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .javaMigrations(new V2__ReconcileLegacySchema())
                .load();
            int migrations = flyway.migrate().migrationsExecuted;
            log.info("customer-work schema migration completed, migrations={}", migrations);
        } catch (Exception e) {
            log.error("customer-work schema migration failed, code={}",
                "PERSISTENCE-SCHEMA-MIGRATION-FAIL", e);
            throw new IllegalStateException("customer-work schema migration failed", e);
        }
    }
}
