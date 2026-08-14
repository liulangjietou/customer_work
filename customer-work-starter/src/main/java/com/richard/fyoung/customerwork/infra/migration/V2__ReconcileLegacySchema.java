package com.richard.fyoung.customerwork.infra.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * V2：把 SchemaInitializer 时代可能存在的结构缺口收敛到统一基线。
 *
 * <p>V1 的 CREATE TABLE IF NOT EXISTS 能补整表，不能给存量表补列。本迁移只处理历史上真实出现过的
 * 增量字段，同时为所有 cw_* 表补齐租户列与租户索引，并重建六个租户内唯一键。所有动作先查
 * information_schema，已符合目标结构时不执行 DDL。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class V2__ReconcileLegacySchema extends BaseJavaMigration {

    private static final String TENANT_COLUMN_DEFINITION =
        "VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户行级隔离）'";

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("2");
    }

    @Override
    public String getDescription() {
        return "reconcile legacy customer-work schema";
    }

    @Override
    public Integer getChecksum() {
        return 1;
    }

    @Override
    public boolean canExecuteInTransaction() {
        // MySQL DDL 会隐式提交，显式声明避免产生“整批可回滚”的错误预期。
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        reconcileKnownColumns(connection);
        reconcileTenantIsolation(connection);
    }

    private void reconcileKnownColumns(Connection connection) throws SQLException {
        ensureColumn(connection, "cw_handoff_ticket", "category", "VARCHAR(64) NULL COMMENT '工单分类（LLM 分类，可空）'");
        ensureColumn(connection, "cw_handoff_ticket", "required_skill", "VARCHAR(64) NULL COMMENT '所需坐席技能标签（LLM 分类，可空）'");
        ensureColumn(connection, "cw_handoff_ticket", "priority", "VARCHAR(16) NULL COMMENT '优先级 LOW/MEDIUM/HIGH/URGENT（LLM 分类，可空）'");
        ensureColumn(connection, "cw_handoff_ticket", "emotion", "VARCHAR(32) NULL COMMENT '用户情绪（LLM 分类，可空）'");
        ensureColumn(connection, "cw_handoff_ticket", "suggested_assignees", "TEXT NULL COMMENT '推荐坐席列表 JSON（HITL 推荐，可空）'");
        ensureColumn(connection, "cw_chat_attachment", "message_id", "VARCHAR(64) NOT NULL DEFAULT '' COMMENT '绑定的用户消息ID（框架Msg.id，空=未绑定）'");
        ensureColumn(connection, "cw_agent_call_log", "cached_tokens", "BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（input_tokens的子集，不计入total）'");
        ensureColumn(connection, "cw_agent_call_log", "model_reported_ms", "BIGINT DEFAULT NULL COMMENT '模型自报耗时合计（毫秒）'");
        ensureColumn(connection, "cw_agent_call_segment", "cached_tokens", "BIGINT DEFAULT NULL COMMENT '命中缓存的输入token（仅MODEL段）'");
        ensureColumn(connection, "cw_agent_call_segment", "model_reported_ms", "BIGINT DEFAULT NULL COMMENT '模型自报耗时（毫秒，仅MODEL段）'");
        ensureColumn(connection, "cw_user", "avatar_url", "VARCHAR(255) COMMENT '头像访问URL（相对路径，可为空）'");
        ensureColumn(connection, "cw_ticket", "last_user_active_at_ms", "BIGINT DEFAULT 0 COMMENT '用户最后活跃时间戳（毫秒，空闲超时巡检基准）'");
        ensureColumn(connection, "cw_eval_run", "seq", "BIGINT NOT NULL AUTO_INCREMENT UNIQUE COMMENT '写入顺序号（同毫秒也不丢序）'");
        ensureColumn(connection, "cw_eval_run", "prompt_fingerprint", "VARCHAR(32) COMMENT '本次运行时生效的提示词指纹'");
    }

    private void reconcileTenantIsolation(Connection connection) throws SQLException {
        for (String table : findCustomerWorkTables(connection)) {
            ensureColumn(connection, table, "tenant_id", TENANT_COLUMN_DEFINITION);
            if (!hasTenantLeadingIndex(connection, table)) {
                String indexName = "idx_" + table.substring("cw_".length()) + "_tenant";
                ensureIndex(connection, table, indexName, Collections.singletonList("tenant_id"), false);
            }
        }

        ensureIndex(connection, "cw_user", "uk_user_username", Arrays.asList("tenant_id", "username"), true);
        ensureIndex(connection, "cw_knowledge", "uk_knowledge_title", Arrays.asList("tenant_id", "title"), true);
        ensureIndex(connection, "cw_sensitive_word", "uk_sensitive_word", Arrays.asList("tenant_id", "word"), true);
        ensureIndex(connection, "cw_rate_limit_rule", "uk_rate_limit_rule_name", Arrays.asList("tenant_id", "rule_name"), true);
        ensureIndex(connection, "cw_dict_type", "uk_dict_type", Arrays.asList("tenant_id", "dict_type"), true);
        ensureIndex(connection, "cw_dict_item", "uk_dict_item", Arrays.asList("tenant_id", "dict_type", "item_key"), true);
    }

    private List<String> findCustomerWorkTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String table = resultSet.getString(1);
                if (table.startsWith("cw_")) {
                    tables.add(table);
                }
            }
        }
        return tables;
    }

    private void ensureColumn(Connection connection, String table, String column, String definition)
        throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        executeDdl(connection, "ALTER TABLE " + quoted(table) + " ADD COLUMN " + quoted(column) + " " + definition);
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
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

    private void ensureIndex(Connection connection, String table, String indexName, List<String> columns,
                             boolean unique) throws SQLException {
        List<String> actualColumns = findIndexColumns(connection, table, indexName);
        if (actualColumns.equals(columns) && indexUniquenessMatches(connection, table, indexName, unique)) {
            return;
        }
        if (!actualColumns.isEmpty()) {
            executeDdl(connection, "ALTER TABLE " + quoted(table) + " DROP INDEX " + quoted(indexName));
        }
        StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(quoted(table)).append(" ADD ");
        if (unique) {
            ddl.append("UNIQUE ");
        }
        ddl.append("INDEX ").append(quoted(indexName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quoted(columns.get(i)));
        }
        ddl.append(')');
        executeDdl(connection, ddl.toString());
    }

    private List<String> findIndexColumns(Connection connection, String table, String indexName)
        throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND index_name = ? ORDER BY seq_in_index";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
        }
        return columns;
    }

    private boolean hasTenantLeadingIndex(Connection connection, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND seq_in_index = 1 AND column_name = 'tenant_id' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean indexUniquenessMatches(Connection connection, String table, String indexName,
                                           boolean unique) throws SQLException {
        String sql = "SELECT non_unique FROM information_schema.statistics WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND index_name = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && (resultSet.getInt(1) == 0) == unique;
            }
        }
    }

    private void executeDdl(Connection connection, String ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private String quoted(String identifier) {
        if (!identifier.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("illegal database identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
