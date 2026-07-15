package com.richard.fyoung.customerwork.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * JDBC 用户账户存储（{@code customer-work.user-auth.store-mode=jdbc} 时启用）。
 *
 * <p>把账户结构化写入 {@code cw_user} 表（username 唯一键）。手法对齐 {@code JdbcHandoffStore}：
 * 构造即 {@code ensureTable} 幂等建表，读写异常一律 {@code catch(Exception)}（HikariPool 初始化异常
 * 是 RuntimeException）。由 {@link UserAccountConfig} 装配，复用 {@code session.mysql.*} 连接配置。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcUserAccountStore implements UserAccountStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserAccountStore.class);

    private static final String COLUMNS = "id, username, password_hash, nickname, phone, status, created_at_ms";

    private static final String INSERT_SQL =
        "INSERT INTO cw_user (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BASE = "SELECT " + COLUMNS + " FROM cw_user";

    private final DataSource dataSource;

    public JdbcUserAccountStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(UserAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setString(1, account.getId());
            ps.setString(2, account.getUsername());
            ps.setString(3, account.getPasswordHash());
            ps.setString(4, account.getNickname());
            ps.setString(5, account.getPhone());
            ps.setString(6, account.getStatus().name());
            ps.setLong(7, account.getCreatedAtMs());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("user save failed, code={}, id={}", "USER-STORE-SAVE-FAIL", account.getId(), e);
            throw new IllegalStateException("failed to save user account: " + account.getId(), e);
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return queryOne(SELECT_BASE + " WHERE username = ?", username, "USER-STORE-FINDBYNAME-FAIL");
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        return queryOne(SELECT_BASE + " WHERE id = ?", id, "USER-STORE-FINDBYID-FAIL");
    }

    private Optional<UserAccount> queryOne(String sql, String param, String errorCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("user query failed, code={}, param={}", errorCode, param, e);
            return Optional.empty();
        }
    }

    private UserAccount mapRow(ResultSet rs) throws SQLException {
        return UserAccount.reconstruct(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getString("phone"),
            UserAccount.Status.valueOf(rs.getString("status")),
            rs.getLong("created_at_ms"));
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_user (
                id VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
                username VARCHAR(64) NOT NULL COMMENT '用户名',
                password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
                nickname VARCHAR(64) COMMENT '昵称',
                phone VARCHAR(32) COMMENT '手机号',
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                UNIQUE KEY uk_user_username (username)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服系统终端用户账户'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("user table ready: cw_user");
        } catch (Exception e) {
            log.error("user ensureTable failed, code={}", "USER-STORE-DDL-FAIL", e);
        }
    }
}
