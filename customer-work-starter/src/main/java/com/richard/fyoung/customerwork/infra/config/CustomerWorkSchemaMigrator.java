package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.infra.migration.V2__ReconcileLegacySchema;
import com.richard.fyoung.customerwork.infra.migration.V9__AddAuditTimestamps;
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
    private static final String ADMIN_QUOTA_MIRROR_VERSION = "5";
    private static final String MEMORY_PRIVACY_MIRROR_VERSION = "10";
    private static final String EVAL_GOVERNANCE_MIRROR_VERSION = "11";
    private static final String CALL_LINEAGE_MIRROR_VERSION = "12";
    private static final String ONLINE_EXPERIMENT_MIRROR_VERSION = "13";
    private static final String SEMANTIC_CACHE_GENERATION_MIRROR_VERSION = "14";
    private static final String USER_SESSION_REVOCATION_MIRROR_VERSION = "15";
    private static final String MODEL_CALL_ATTRIBUTION_MIRROR_VERSION = "16";
    private static final String HANDOFF_AUTHORITY_MIRROR_VERSION = "17";
    private static final String EVAL_DATASET_RELEASE_MIRROR_VERSION = "18";
    private static final String AGENT_REPLAY_SNAPSHOT_MIRROR_VERSION = "19";
    private static final String MODEL_COST_SETTLEMENT_MIRROR_VERSION = "20";
    private static final String BADCASE_RECURRENCE_SIGNAL_MIRROR_VERSION = "21";
    private static final String COLLATION_ALIGNMENT_MIRROR_VERSION = "22";

    /** 两库 CREATE DATABASE 声明的排序规则，V22 起全部 cw_* 表对齐于此。 */
    private static final String TARGET_COLLATION = "utf8mb4_unicode_ci";

    private final DataSource dataSource;
    private final boolean enabled;

    /**
     * 为已经持有客服端数据源的宿主执行强制迁移。
     *
     * <p>典型场景是 Admin 的惰性跨库门面：它不加载 starter 自动装配，但在真正暴露客服端
     * Mapper 前仍必须先把同一套权威 schema 迁到当前版本。</p>
     */
    public CustomerWorkSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        this.enabled = true;
    }

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
                .javaMigrations(new V2__ReconcileLegacySchema(), new V9__AddAuditTimestamps())
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
     * 从版本 4 接管。V10 以后每个结构迁移继续以关键表/列逐级判定；普通旧库仍从 0
     * 开始执行全部修复。</p>
     *
     * <p><b>幂等的数据迁移可以不判定</b>：V6（运营分区归一）在空镜像库上影响 0 行、重跑结果相同，
     * 因此没有为它加判定——镜像库会重跑它一次，多一行历史而已。判定要写成"列注释里有没有某几个字"
     * 才能识别它，那比多一行历史脆得多。反过来说，<b>只要新加的数据迁移不是幂等的，就必须在这里补判定</b>。</p>
     *
     * <p><b>V5 只能靠数据判断</b>：它是纯种子迁移（给 {@code cw_subject_quota_level} 加后台档），
     * 没有任何结构变化，用 {@code tableExists}/{@code columnExists} 是看不出来的。只按结构判定的话，
     * 完整镜像会被当成"停在 V4"而重新执行 V5，撞上唯一键直接迁移失败。往后再加纯数据迁移，
     * 同样要在这里补一条对应的数据判定。</p>
     *
     * <p>V20、V21 虽然可以重入，但都包含存量数据汇总或回填。完整镜像必须按索引等末端结构确认
     * 脚本已完整执行后直接接管，避免每次首次启动都扫描业务表。</p>
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
            if (!subjectQuotaMirror) {
                return OUTBOX_MIRROR_VERSION;
            }
            boolean adminQuotaMirror = rowExists(connection,
                "SELECT 1 FROM `cw_subject_quota_level` WHERE `level_code` = 'admin-default' LIMIT 1");
            if (!adminQuotaMirror) {
                return SUBJECT_QUOTA_MIRROR_VERSION;
            }
            boolean memoryPrivacyMirror = tableExists(connection, "cw_memory_consent")
                && columnExists(connection, "cw_memory_consent", "scope_id");
            if (!memoryPrivacyMirror) {
                return ADMIN_QUOTA_MIRROR_VERSION;
            }
            boolean evalGovernanceMirror = tableExists(connection, "cw_eval_dataset_version")
                && columnExists(connection, "cw_eval_run", "dataset_version_id")
                && columnExists(connection, "cw_eval_run", "dataset_fingerprint")
                && columnExists(connection, "cw_eval_run", "version_binding_json");
            if (!evalGovernanceMirror) {
                return MEMORY_PRIVACY_MIRROR_VERSION;
            }
            boolean callLineageMirror = columnExists(connection, "cw_agent_call_log", "trace_id")
                && columnExists(connection, "cw_agent_call_log", "runtime_revision")
                && columnExists(connection, "cw_agent_call_log", "runtime_content_hash")
                && columnExists(connection, "cw_agent_call_log", "version_binding_json");
            if (!callLineageMirror) {
                return EVAL_GOVERNANCE_MIRROR_VERSION;
            }
            boolean onlineExperimentMirror = columnExists(connection, "cw_agent_call_log", "experiment_id")
                && columnExists(connection, "cw_agent_call_log", "experiment_revision")
                && columnExists(connection, "cw_agent_call_log", "experiment_arm")
                && columnExists(connection, "cw_agent_call_log", "experiment_deployment_id")
                && columnExists(connection, "cw_agent_call_log", "experiment_bucket");
            if (!onlineExperimentMirror) {
                return CALL_LINEAGE_MIRROR_VERSION;
            }
            boolean semanticCacheGenerationMirror =
                columnExists(connection, "cw_semantic_cache", "config_generation");
            if (!semanticCacheGenerationMirror) {
                return ONLINE_EXPERIMENT_MIRROR_VERSION;
            }
            if (!columnExists(connection, "cw_user", "session_epoch")) {
                return SEMANTIC_CACHE_GENERATION_MIRROR_VERSION;
            }
            if (!columnExists(connection, "cw_agent_call_segment", "pricing_status")) {
                return USER_SESSION_REVOCATION_MIRROR_VERSION;
            }
            boolean handoffAuthorityMirror = columnExists(connection, "cw_ticket", "routing_category")
                && columnExists(connection, "cw_ticket", "required_skill")
                && columnExists(connection, "cw_ticket", "routing_priority")
                && columnExists(connection, "cw_ticket", "emotion")
                && columnExists(connection, "cw_ticket", "suggested_assignees");
            if (!handoffAuthorityMirror) {
                return MODEL_CALL_ATTRIBUTION_MIRROR_VERSION;
            }
            boolean evalDatasetReleaseMirror = tableExists(connection, "cw_eval_dataset_release")
                && columnExists(connection, "cw_eval_dataset_release", "snapshot_version_id")
                && columnExists(connection, "cw_eval_dataset_release", "content_hash")
                && columnExists(connection, "cw_eval_dataset_release", "status");
            if (!evalDatasetReleaseMirror) {
                return HANDOFF_AUTHORITY_MIRROR_VERSION;
            }
            if (!columnExists(connection, "cw_agent_call_log", "replay_snapshot_json")) {
                return EVAL_DATASET_RELEASE_MIRROR_VERSION;
            }
            boolean modelCostSettlementMirror =
                columnExists(connection, "cw_agent_call_segment", "cost_amount")
                    && columnExists(connection, "cw_agent_call_segment", "cost_currency")
                    && columnExists(connection, "cw_agent_call_segment", "cost_status")
                    && columnExists(connection, "cw_agent_call_log", "model_cost_amount")
                    && columnExists(connection, "cw_agent_call_log", "model_cost_currency")
                    && columnExists(connection, "cw_agent_call_log", "model_cost_status")
                    && columnExists(connection, "cw_agent_call_log", "model_segment_count")
                    && columnExists(connection, "cw_agent_call_log", "settled_cost_segment_count")
                    && columnExists(connection, "cw_agent_call_log", "unsettled_cost_segment_count")
                    && indexExists(connection, "cw_agent_call_log", "idx_agent_call_cost_window");
            if (!modelCostSettlementMirror) {
                return AGENT_REPLAY_SNAPSHOT_MIRROR_VERSION;
            }
            boolean badcaseRecurrenceSignalMirror = columnExists(connection, "cw_badcase", "signal_hash")
                && indexExists(connection, "cw_badcase", "idx_badcase_signal");
            if (!badcaseRecurrenceSignalMirror) {
                return MODEL_COST_SETTLEMENT_MIRROR_VERSION;
            }
            // V22 只改排序规则、不改结构，因此判定信号就是排序规则本身：一张不合规的表都没有，
            // 就说明这个库（无论是新镜像还是已执行过 V22 的旧库）已经在 V22 的终态上。
            return allBusinessTablesUseTargetCollation(connection)
                ? COLLATION_ALIGNMENT_MIRROR_VERSION : BADCASE_RECURRENCE_SIGNAL_MIRROR_VERSION;
        }
    }

    /** 数据维度的镜像判定：纯种子迁移没有结构痕迹，只能问"那行数据在不在"。 */
    private boolean rowExists(Connection connection, String sql) throws Exception {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
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

    /**
     * 全部 cw_* 业务表是否都已落在 {@link #TARGET_COLLATION}。
     *
     * <p>V22 是纯排序规则对齐，没有任何结构痕迹可查——用 {@code tableExists}/{@code columnExists}
     * 判不出来，只按结构判定会让完整镜像被当成"停在 V21"而重跑一次 V22（多一行历史，
     * 且白扫 47 张表）。这里直接问 information_schema 要排序规则本身。</p>
     */
    private boolean allBusinessTablesUseTargetCollation(Connection connection) throws Exception {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
            + "AND table_type = 'BASE TABLE' AND table_name LIKE 'cw\\_%' "
            + "AND table_collation <> ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TARGET_COLLATION);
            try (ResultSet resultSet = statement.executeQuery()) {
                return !resultSet.next();
            }
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws Exception {
        String sql = "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
