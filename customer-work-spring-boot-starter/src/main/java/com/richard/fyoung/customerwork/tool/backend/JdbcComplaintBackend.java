package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 投诉工单后端的 JDBC 参考实现：投诉工单真实落库到 {@code cw_complaint}。
 *
 * <p>输出文案对齐 {@link MockComplaintBackend}；种子含一条演示工单（CP-seed-0001），支撑 queryComplaint
 * 无需先 fileComplaint 即可演示。本类<b>不注册为 Spring Bean</b>——由 app 层按 {@code @ConditionalOnProperty} 装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcComplaintBackend implements ComplaintBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcComplaintBackend.class);

    private static final String ID_PREFIX = "CP";

    /** 工单状态：处理中 / 已回复。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final DataSource dataSource;

    public JdbcComplaintBackend(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public Mono<String> fileComplaint(String orderId, String content) {
        return Mono.fromSupplier(() -> doFileComplaint(orderId, content));
    }

    @Override
    public Mono<String> queryComplaint(String ticketId) {
        return Mono.fromSupplier(() -> doQueryComplaint(ticketId));
    }

    private String doFileComplaint(String orderId, String content) {
        String complaintNo = ID_PREFIX + System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO cw_complaint (complaint_no, order_id, content, status, created_at_ms) "
                 + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, complaintNo);
            ps.setString(2, orderId);
            ps.setString(3, content);
            ps.setString(4, STATUS_PROCESSING);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            log.info("file complaint: ticket={}, order={}", complaintNo, orderId);
            return "已为您创建投诉工单 " + complaintNo + "（订单 " + orderId + "），"
                + "我们将在 24 小时内跟进处理，结果将短信通知您。";
        } catch (Exception e) {
            log.error("file complaint failed, code={}, orderId={}", "COMPLAINT-BACKEND-FILE-FAIL", orderId, e);
            return "投诉工单系统暂时不可用，已为您转接人工坐席。";
        }
    }

    private String doQueryComplaint(String ticketId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT status FROM cw_complaint WHERE complaint_no = ?")) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到投诉工单 " + ticketId + "，请核对工单号。";
                }
                String status = rs.getString("status");
                if (STATUS_RESOLVED.equals(status)) {
                    return "投诉工单 " + ticketId + " 当前状态：已处理完毕，感谢您的反馈。";
                }
                return "投诉工单 " + ticketId + " 当前状态：处理中，已分派至对应部门，预计 1 个工作日内回复。";
            }
        } catch (Exception e) {
            log.error("query complaint failed, code={}, ticketId={}", "COMPLAINT-BACKEND-QUERY-FAIL", ticketId, e);
            return "投诉工单查询暂时不可用，建议稍后再试。";
        }
    }

    /** 自动建表 + INSERT IGNORE 种子数据（幂等）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_complaint (
                complaint_no VARCHAR(64) PRIMARY KEY COMMENT '投诉工单号',
                order_id VARCHAR(32) COMMENT '关联订单号（可空）',
                content TEXT COMMENT '投诉内容',
                status VARCHAR(16) NOT NULL COMMENT '状态：PROCESSING/RESOLVED',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                INDEX idx_complaint_order (order_id, created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉工单（JDBC 后端演示表）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            seed();
            log.info("complaint table ready: cw_complaint");
        } catch (Exception e) {
            log.error("complaint ensureTable failed, code={}", "COMPLAINT-BACKEND-DDL-FAIL", e);
        }
    }

    /** INSERT IGNORE 种子投诉工单（CP-seed-0001，支撑 queryComplaint 演示）。 */
    private void seed() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT IGNORE INTO cw_complaint (complaint_no, order_id, content, status, created_at_ms) "
                 + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "CP-seed-0001");
            ps.setString(2, "20260613002");
            ps.setString(3, "物流配送太慢，希望加快处理");
            ps.setString(4, STATUS_PROCESSING);
            ps.setLong(5, 1779235200000L);
            ps.executeUpdate();
        }
    }
}
