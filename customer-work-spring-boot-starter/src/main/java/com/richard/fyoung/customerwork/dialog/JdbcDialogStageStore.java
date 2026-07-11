package com.richard.fyoung.customerwork.dialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/**
 * JDBC 对话阶段存储（生产参考实现：补齐 {@code dialog.store-mode=jdbc} 的跨实例共享落地）。
 *
 * <p>把会话当前阶段写入 {@code cw_dialog_stage} 表，多实例部署下请求被负载均衡到不同实例时
 * 仍能读到同一份阶段状态——解决进程内存储"阶段归零回 GREETING"的问题。</p>
 *
 * <p>由 {@link DialogStageConfig} 按 {@code dialog.store-mode=jdbc} 自动装配，复用
 * {@code session.mysql.*} 连接配置。建表 SQL 见 {@code mysql/schema.sql} 中的
 * {@code cw_dialog_stage} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcDialogStageStore implements DialogStageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDialogStageStore.class);

    private static final String UPSERT_SQL =
        "INSERT INTO cw_dialog_stage (session_id, stage) VALUES (?, ?) "
        + "ON DUPLICATE KEY UPDATE stage=VALUES(stage)";

    private final DataSource dataSource;

    public JdbcDialogStageStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public Optional<DialogStage> find(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT stage FROM cw_dialog_stage WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(DialogStage.valueOf(rs.getString("stage"))) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("[JdbcDialogStageStore] find failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-FIND-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String sessionId, DialogStage stage) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, sessionId);
            ps.setString(2, stage.name());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[JdbcDialogStageStore] set failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-SET-FAIL", sessionId, e);
        }
    }

    @Override
    public void remove(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_dialog_stage WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[JdbcDialogStageStore] remove failed, errorCode={}, sessionId={}",
                "DIALOGSTAGE-STORE-REMOVE-FAIL", sessionId, e);
        }
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_dialog_stage (
                session_id VARCHAR(191) PRIMARY KEY COMMENT '会话 ID',
                stage VARCHAR(24) NOT NULL COMMENT '当前对话阶段：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话阶段状态机（多实例共享）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcDialogStageStore] 对话阶段表已就绪: cw_dialog_stage");
        } catch (Exception e) {
            // 捕获 Exception：连接池首次取连接失败（MySQL 冷启动瞬时不可达）抛出的是
            // HikariPool$PoolInitializationException（RuntimeException），非 SQLException
            log.error("[JdbcDialogStageStore] ensureTable failed, errorCode={}", "DIALOGSTAGE-STORE-DDL-FAIL", e);
        }
    }
}
