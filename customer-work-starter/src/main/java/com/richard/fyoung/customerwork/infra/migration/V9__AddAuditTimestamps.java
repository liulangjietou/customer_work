package com.richard.fyoung.customerwork.infra.migration;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * V9：为缺少标准审计时间的客服端业务表补齐创建时间和修改时间。
 *
 * <p>MySQL DDL 会隐式提交，无法依赖迁移事务回滚。因此先校验全部目标表都存在，再按表查询
 * information_schema，并把同一张表缺失的列合并到一条 ALTER TABLE 中。迁移中途失败并完成
 * Flyway repair 后重试时，已完成的表会因列已存在而跳过。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class V9__AddAuditTimestamps extends BaseJavaMigration {

    private static final List<String> AUDIT_TABLES = Arrays.asList(
        "cw_slot_filling_progress",
        "cw_dialog_stage",
        "cw_agent_call_segment",
        "cw_product",
        "cw_member",
        "cw_knowledge",
        "cw_fact_log",
        "cw_prompt_version",
        "cw_csat_survey",
        "cw_knowledge_gap"
    );

    private static final String CREATED_AT_DEFINITION =
        "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间'";
    private static final String UPDATED_AT_DEFINITION =
        "DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) "
            + "COMMENT '记录最后修改时间'";

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("9");
    }

    @Override
    public String getDescription() {
        return "add customer-work audit timestamps";
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
        verifyTablesExist(connection);
        for (String table : AUDIT_TABLES) {
            addMissingAuditColumns(connection, table);
        }
    }

    private void verifyTablesExist(Connection connection) throws SQLException {
        for (String table : AUDIT_TABLES) {
            if (!tableExists(connection, table)) {
                throw new SQLException("required customer-work table does not exist: " + table);
            }
        }
    }

    private void addMissingAuditColumns(Connection connection, String table) throws SQLException {
        boolean createdAtExists = columnExists(connection, table, "created_at");
        boolean updatedAtExists = columnExists(connection, table, "updated_at");
        if (createdAtExists && updatedAtExists) {
            return;
        }

        StringBuilder ddl = new StringBuilder("ALTER TABLE ").append(quoted(table));
        if (!createdAtExists) {
            ddl.append(" ADD COLUMN `created_at` ").append(CREATED_AT_DEFINITION);
        }
        if (!updatedAtExists) {
            if (!createdAtExists) {
                ddl.append(',');
            }
            ddl.append(" ADD COLUMN `updated_at` ").append(UPDATED_AT_DEFINITION);
        }
        executeDdl(connection, ddl.toString());
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() "
            + "AND table_name = ? AND table_type = 'BASE TABLE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
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
