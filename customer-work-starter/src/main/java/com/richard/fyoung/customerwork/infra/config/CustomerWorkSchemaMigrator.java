package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.infra.migration.V2__ReconcileLegacySchema;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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
    private static final String LEGACY_BASELINE_VERSION = "0";
    private static final String OUTBOX_MIRROR_VERSION = "3";
    private static final String SUBJECT_QUOTA_MIRROR_VERSION = "4";

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
            String baselineVersion = resolveBaselineVersion();
            Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(FLYWAY_LOCATIONS)
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion(baselineVersion))
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

    /**
     * 判断当前非空库是否由完整 schema 镜像初始化。
     *
     * <p>完整镜像会持续同步最新迁移，因此接管版本不能写死。先核对 V1-V3 的完整结构，再用 V4
     * 的表与列判断镜像是否已经包含主体配额；旧的 V3 镜像仍从版本 3 接管并补跑 V4，当前镜像则
     * 从版本 4 接管。普通旧库仍从 0 开始执行全部修复。</p>
     */
    private String resolveBaselineVersion() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (tableExists(connection, "flyway_schema_history")) {
                return LEGACY_BASELINE_VERSION;
            }
            boolean outboxMirror = tableExists(connection, "cw_outbox_message")
                && columnExists(connection, "cw_outbox_message", "lease_owner")
                && columnExists(connection, "cw_outbox_message", "lease_until_ms")
                && columnExists(connection, "cw_dead_letter", "lease_owner")
                && columnExists(connection, "cw_dead_letter", "lease_until_ms")
                && columnExists(connection, "cw_user", "avatar_url")
                && columnExists(connection, "cw_ticket", "last_user_active_at_ms")
                && columnExists(connection, "cw_chat_attachment", "message_id")
                && columnExists(connection, "cw_eval_run", "seq")
                && columnExists(connection, "cw_eval_run", "prompt_fingerprint")
                && columnExists(connection, "cw_agent_call_log", "cached_tokens")
                && columnExists(connection, "cw_agent_call_segment", "cached_tokens");
            if (!outboxMirror) {
                return LEGACY_BASELINE_VERSION;
            }
            boolean subjectQuotaMirror = tableExists(connection, "cw_subject_quota_level")
                && tableExists(connection, "cw_subject_quota_hit")
                && columnExists(connection, "cw_user", "level_code");
            return subjectQuotaMirror
                ? SUBJECT_QUOTA_MIRROR_VERSION : OUTBOX_MIRROR_VERSION;
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND table_type = 'BASE TABLE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
