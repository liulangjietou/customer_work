package com.richard.fyoung.customerwork.handoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 人机切换工单存储（生产参考实现：补齐 {@code human-handoff.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把工单结构化写入 {@code cw_handoff_ticket} 表，保证应用重启 / 多实例部署下工单不丢失——
 * 坐席在实例 A 接单、坐席工作台轮询落到实例 B 也应看到最新状态（进程内 {@link InMemoryHandoffStore}
 * 无此保证）。</p>
 *
 * <p>由 {@link HandoffConfig} 按 {@code human-handoff.store-mode=jdbc} 自动装配，复用
 * {@code session.mysql.*} 的连接配置构建独立连接池（与会话持久化共享同一 MySQL 实例）。</p>
 *
 * <p>建表 SQL 见 {@code mysql/schema.sql} 中的 {@code cw_handoff_ticket} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcHandoffStore implements HandoffStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcHandoffStore.class);

    private static final String UPSERT_SQL =
        "INSERT INTO cw_handoff_ticket (id, session_id, reason, created_at_ms, "
        + "status, claimed_by, claimed_at_ms, resolution_note, resolved_at_ms) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE status=VALUES(status), claimed_by=VALUES(claimed_by), "
        + "claimed_at_ms=VALUES(claimed_at_ms), resolution_note=VALUES(resolution_note), "
        + "resolved_at_ms=VALUES(resolved_at_ms)";

    private static final String SELECT_BASE =
        "SELECT id, session_id, reason, created_at_ms, status, claimed_by, claimed_at_ms, "
        + "resolution_note, resolved_at_ms FROM cw_handoff_ticket";

    private final DataSource dataSource;

    public JdbcHandoffStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(HandoffTicket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, ticket.getId());
            ps.setString(2, ticket.getSessionId());
            ps.setString(3, ticket.getReason());
            ps.setLong(4, ticket.getCreatedAtMs());
            ps.setString(5, ticket.getStatus().name());
            ps.setString(6, ticket.getClaimedBy());
            ps.setLong(7, ticket.getClaimedAtMs());
            ps.setString(8, ticket.getResolutionNote());
            ps.setLong(9, ticket.getResolvedAtMs());
            ps.executeUpdate();
        } catch (Exception e) {
            // 捕获 Exception：连接池获取连接失败时抛出 HikariPool$PoolInitializationException
            // （RuntimeException），非 SQLException 子类，必须一并兜住
            log.error("[JdbcHandoffStore] save failed, errorCode={}, id={}",
                "HANDOFF-STORE-SAVE-FAIL", ticket.getId(), e);
            throw new IllegalStateException("failed to save handoff ticket: " + ticket.getId(), e);
        }
    }

    @Override
    public void update(HandoffTicket ticket) {
        save(ticket);
    }

    @Override
    public Optional<HandoffTicket> find(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("[JdbcHandoffStore] find failed, errorCode={}, id={}", "HANDOFF-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<HandoffTicket> findAll() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE);
             ResultSet rs = ps.executeQuery()) {
            List<HandoffTicket> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (Exception e) {
            log.error("[JdbcHandoffStore] findAll failed, errorCode={}", "HANDOFF-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<HandoffTicket> findByStatus(HandoffStatus status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<HandoffTicket> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("[JdbcHandoffStore] findByStatus failed, errorCode={}, status={}",
                "HANDOFF-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    private HandoffTicket mapRow(ResultSet rs) throws SQLException {
        return HandoffTicket.reconstruct(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("reason"),
            rs.getLong("created_at_ms"),
            HandoffStatus.valueOf(rs.getString("status")),
            rs.getString("claimed_by"),
            rs.getLong("claimed_at_ms"),
            rs.getString("resolution_note"),
            rs.getLong("resolved_at_ms"));
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_handoff_ticket (
                id VARCHAR(64) PRIMARY KEY COMMENT '工单号',
                session_id VARCHAR(128) COMMENT '关联会话',
                reason TEXT COMMENT '转人工原因',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                status VARCHAR(16) NOT NULL COMMENT 'PENDING/CLAIMED/RESOLVED',
                claimed_by VARCHAR(64) COMMENT '接单坐席',
                claimed_at_ms BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
                resolution_note TEXT COMMENT '处理结果备注',
                resolved_at_ms BIGINT DEFAULT 0 COMMENT '结案时间戳（毫秒）',
                INDEX idx_handoff_status (status),
                INDEX idx_handoff_created (created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人机切换工单（AI 转人工闭环）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcHandoffStore] 人机切换工单表已就绪: cw_handoff_ticket");
        } catch (Exception e) {
            // 捕获 Exception 而非 SQLException：连接池首次取连接失败（如 MySQL 冷启动瞬时不可达）
            // 抛出的是 HikariPool$PoolInitializationException（RuntimeException），不属于 SQLException；
            // 建表失败不应阻断整个 Bean 装配——后续每次读写自行重试连接并各自记录 SQL 异常。
            log.error("[JdbcHandoffStore] ensureTable failed, errorCode={}", "HANDOFF-STORE-DDL-FAIL", e);
        }
    }
}
