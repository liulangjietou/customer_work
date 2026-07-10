package com.richard.fyoung.customerwork.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JDBC 审计落地实现（P3：结构化存储审计轨迹）。
 *
 * <p>把审计事件结构化写入数据库表 {@code cw_audit_log}，支持按类型、时间、Agent 维度查询，
 * 便于合规审计追溯与数据分析。</p>
 *
 * <p>使用方式：下游声明 {@code DataSource} Bean + {@code JdbcAuditSink} Bean 即可覆盖默认的
 * {@link LoggingAuditSink}（日志输出）。</p>
 *
 * <p>建表 SQL 见 {@code mysql/schema.sql} 中的 {@code cw_audit_log} 表。</p>
 *
 * <pre>{@code
 * @Bean
 * public AuditSink auditSink(DataSource dataSource) {
 *     return new JdbcAuditSink(dataSource);
 * }
 * }</pre>
 * @author owlzhangfq@gmail.com
 */
public class JdbcAuditSink implements AuditSink, AuditQuery {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditSink.class);

    private static final String INSERT_SQL =
        "INSERT INTO cw_audit_log (event_type, agent_name, event_data, created_at) VALUES (?, ?, ?, ?)";

    /**
     * 按会话查询：agent_name 形如 {@code CustomerServiceAgent-<sessionId>}，用后缀 LIKE 匹配，
     * 时间倒序取最近 N 条。ESCAPE 转义 sessionId 里可能出现的 LIKE 通配符（%、_）。
     */
    private static final String QUERY_BY_SESSION_SQL =
        "SELECT event_type, agent_name, event_data, created_at FROM cw_audit_log "
        + "WHERE agent_name LIKE ? ESCAPE '\\\\' ORDER BY created_at DESC, id DESC LIMIT ?";

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcAuditSink(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void record(String type, Map<String, Object> fields) {
        if (type == null || fields == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setString(1, type);
            // agent_name 从 fields 中提取（如果存在）
            ps.setString(2, String.valueOf(fields.getOrDefault("agent", "")));
            ps.setString(3, mapper.writeValueAsString(fields));
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("[JdbcAuditSink] 写入审计记录失败（已忽略）: {}", e.getMessage());
        }
    }

    @Override
    public List<AuditRecord> queryBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        String pattern = "%" + escapeLike(sessionId);
        List<AuditRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(QUERY_BY_SESSION_SQL)) {
            ps.setString(1, pattern);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    records.add(new AuditRecord(
                        rs.getString("event_type"),
                        rs.getString("agent_name"),
                        rs.getString("event_data"),
                        ts == null ? 0L : ts.getTime()));
                }
            }
        } catch (Exception e) {
            log.error("[JdbcAuditSink] query audit by session failed, code={}, sessionId={}",
                "AUDIT_QUERY_ERROR", sessionId, e);
        }
        return records;
    }

    /** 转义 LIKE 通配符，避免 sessionId 中的 % / _ / \ 被当作模式匹配符。 */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_audit_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL COMMENT '事件类型: tool-call / final-answer / error',
                agent_name VARCHAR(128) DEFAULT '' COMMENT 'Agent 名称',
                event_data TEXT COMMENT '结构化事件字段 JSON',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
                INDEX idx_audit_type (event_type),
                INDEX idx_audit_created (created_at),
                INDEX idx_audit_agent (agent_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合规审计轨迹（结构化存储）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcAuditSink] 审计表已就绪: cw_audit_log");
        } catch (SQLException e) {
            log.warn("[JdbcAuditSink] 建表失败（可能表已存在或无 DDL 权限）: {}", e.getMessage());
        }
    }
}
