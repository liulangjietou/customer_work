package com.richard.fyoung.customerwork.approval;

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
 * JDBC 审批工单存储（生产参考实现：补齐 {@code human-approval.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把审批工单结构化写入 {@code cw_approval} 表，保证应用重启 / 多实例部署下审批单不丢失——
 * 这对涉及资金的退款审批至关重要（进程内 {@link InMemoryApprovalStore} 重启即清空）。</p>
 *
 * <p>由 {@link ApprovalConfig} 按 {@code human-approval.store-mode=jdbc} 自动装配，复用
 * {@code session.mysql.*} 的连接配置构建独立连接池（与会话持久化共享同一 MySQL 实例）。</p>
 *
 * <p>建表 SQL 见 {@code mysql/schema.sql} 中的 {@code cw_approval} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalStore.class);

    private static final String UPSERT_SQL =
        "INSERT INTO cw_approval (id, type, session_id, order_id, amount, reason, created_at_ms, "
        + "status, operator, decision_note, decided_at_ms, execution_status, execution_failure_reason, "
        + "execution_attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE status=VALUES(status), operator=VALUES(operator), "
        + "decision_note=VALUES(decision_note), decided_at_ms=VALUES(decided_at_ms), "
        + "execution_status=VALUES(execution_status), "
        + "execution_failure_reason=VALUES(execution_failure_reason), "
        + "execution_attempts=VALUES(execution_attempts)";

    private static final String SELECT_BASE =
        "SELECT id, type, session_id, order_id, amount, reason, created_at_ms, status, operator, "
        + "decision_note, decided_at_ms, execution_status, execution_failure_reason, execution_attempts "
        + "FROM cw_approval";

    private final DataSource dataSource;

    public JdbcApprovalStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(ApprovalRequest request) {
        if (request == null || request.getId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, request.getId());
            ps.setString(2, request.getType().name());
            ps.setString(3, request.getSessionId());
            ps.setString(4, request.getOrderId());
            ps.setString(5, request.getAmount());
            ps.setString(6, request.getReason());
            ps.setLong(7, request.getCreatedAtMs());
            ps.setString(8, request.getStatus().name());
            ps.setString(9, request.getOperator());
            ps.setString(10, request.getDecisionNote());
            ps.setLong(11, request.getDecidedAtMs());
            ps.setString(12, request.getExecutionStatus().name());
            ps.setString(13, request.getExecutionFailureReason());
            ps.setInt(14, request.getExecutionAttempts());
            ps.executeUpdate();
        } catch (Exception e) {
            // 捕获 Exception：连接池获取连接失败时抛出 HikariPool$PoolInitializationException
            // （RuntimeException），非 SQLException 子类，必须一并兜住
            log.error("[JdbcApprovalStore] save failed, errorCode={}, id={}",
                "APPROVAL-STORE-SAVE-FAIL", request.getId(), e);
            throw new IllegalStateException("failed to save approval: " + request.getId(), e);
        }
    }

    @Override
    public void update(ApprovalRequest request) {
        save(request);
    }

    @Override
    public Optional<ApprovalRequest> find(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("[JdbcApprovalStore] find failed, errorCode={}, id={}", "APPROVAL-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ApprovalRequest> findAll() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE);
             ResultSet rs = ps.executeQuery()) {
            List<ApprovalRequest> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (Exception e) {
            log.error("[JdbcApprovalStore] findAll failed, errorCode={}", "APPROVAL-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<ApprovalRequest> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("[JdbcApprovalStore] findByStatus failed, errorCode={}, status={}",
                "APPROVAL-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    @Override
    public void delete(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cw_approval WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("[JdbcApprovalStore] delete failed, errorCode={}, id={}", "APPROVAL-STORE-DELETE-FAIL", id, e);
        }
    }

    private ApprovalRequest mapRow(ResultSet rs) throws SQLException {
        return ApprovalRequest.reconstruct(
            rs.getString("id"),
            ApprovalType.valueOf(rs.getString("type")),
            rs.getString("session_id"),
            rs.getString("order_id"),
            rs.getString("amount"),
            rs.getString("reason"),
            rs.getLong("created_at_ms"),
            ApprovalStatus.valueOf(rs.getString("status")),
            rs.getString("operator"),
            rs.getString("decision_note"),
            rs.getLong("decided_at_ms"),
            ExecutionStatus.valueOf(rs.getString("execution_status")),
            rs.getString("execution_failure_reason"),
            rs.getInt("execution_attempts"));
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_approval (
                id VARCHAR(64) PRIMARY KEY COMMENT '审批单号',
                type VARCHAR(32) NOT NULL COMMENT '审批类型：REFUND 等',
                session_id VARCHAR(128) COMMENT '关联会话',
                order_id VARCHAR(64) COMMENT '关联订单号',
                amount VARCHAR(32) COMMENT '涉及金额',
                reason TEXT COMMENT '诉求原因',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                status VARCHAR(16) NOT NULL COMMENT 'PENDING/APPROVED/DENIED',
                operator VARCHAR(64) COMMENT '决策操作员',
                decision_note TEXT COMMENT '决策备注',
                decided_at_ms BIGINT DEFAULT 0 COMMENT '决策时间戳（毫秒）',
                execution_status VARCHAR(24) DEFAULT 'NOT_APPLICABLE' COMMENT '下游执行状态',
                execution_failure_reason TEXT COMMENT '下游执行失败原因',
                execution_attempts INT DEFAULT 0 COMMENT '下游执行尝试次数',
                INDEX idx_approval_status (status),
                INDEX idx_approval_created (created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工审批工单（退款放行等资金动作）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcApprovalStore] 审批工单表已就绪: cw_approval");
        } catch (Exception e) {
            // 捕获 Exception 而非 SQLException：连接池首次取连接失败（如 MySQL 冷启动瞬时不可达）
            // 抛出的是 HikariPool$PoolInitializationException（RuntimeException），不属于 SQLException；
            // 建表失败不应阻断整个 Bean 装配——后续每次读写自行重试连接并各自记录 SQL 异常。
            log.error("[JdbcApprovalStore] ensureTable failed, errorCode={}", "APPROVAL-STORE-DDL-FAIL", e);
        }
    }
}
