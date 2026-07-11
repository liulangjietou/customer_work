package com.richard.fyoung.customerwork.feedback;

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
 * JDBC 用户反馈存储（生产参考实现：补齐 {@code feedback.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把消息级反馈结构化写入 {@code cw_message_feedback} 表，保证应用重启 / 多实例部署下反馈不丢失
 * （进程内 {@link InMemoryFeedbackStore} 重启即清空）。</p>
 *
 * <p>由 {@link FeedbackConfig} 按 {@code feedback.store-mode=jdbc} 自动装配，复用
 * {@code session.mysql.*} 的连接配置构建独立连接池。建表 SQL 见
 * {@code mysql/schema.sql} 中的 {@code cw_message_feedback} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcFeedbackStore implements FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFeedbackStore.class);

    private static final String UPSERT_SQL =
        "INSERT INTO cw_message_feedback (message_id, session_id, type, comment, created_at_ms) "
        + "VALUES (?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE session_id=VALUES(session_id), type=VALUES(type), "
        + "comment=VALUES(comment), created_at_ms=VALUES(created_at_ms)";

    private static final String SELECT_BASE =
        "SELECT message_id, session_id, type, comment, created_at_ms FROM cw_message_feedback";

    private final DataSource dataSource;

    public JdbcFeedbackStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(MessageFeedback feedback) {
        if (feedback == null || feedback.messageId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, feedback.messageId());
            ps.setString(2, feedback.sessionId());
            ps.setString(3, feedback.type().name());
            ps.setString(4, feedback.comment());
            ps.setLong(5, feedback.createdAtMs());
            ps.executeUpdate();
        } catch (Exception e) {
            // 捕获 Exception：连接池获取连接失败时抛出 HikariPool$PoolInitializationException
            // （RuntimeException），非 SQLException 子类，必须一并兜住
            log.error("[JdbcFeedbackStore] save failed, errorCode={}, messageId={}",
                "FEEDBACK-STORE-SAVE-FAIL", feedback.messageId(), e);
        }
    }

    @Override
    public Optional<MessageFeedback> find(String messageId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("[JdbcFeedbackStore] find failed, errorCode={}, messageId={}",
                "FEEDBACK-STORE-FIND-FAIL", messageId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<MessageFeedback> findBySession(String sessionId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 SELECT_BASE + " WHERE session_id = ? ORDER BY created_at_ms ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<MessageFeedback> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("[JdbcFeedbackStore] findBySession failed, errorCode={}, sessionId={}",
                "FEEDBACK-STORE-FINDBYSESSION-FAIL", sessionId, e);
            return List.of();
        }
    }

    private MessageFeedback mapRow(ResultSet rs) throws SQLException {
        return new MessageFeedback(
            rs.getString("message_id"),
            rs.getString("session_id"),
            FeedbackType.valueOf(rs.getString("type")),
            rs.getString("comment"),
            rs.getLong("created_at_ms"));
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_message_feedback (
                message_id VARCHAR(64) PRIMARY KEY COMMENT '被反馈的消息ID',
                session_id VARCHAR(128) COMMENT '所属会话',
                type VARCHAR(8) NOT NULL COMMENT 'UP/DOWN',
                comment TEXT COMMENT '文字说明',
                created_at_ms BIGINT NOT NULL COMMENT '提交时间戳（毫秒，重复提交取最新）',
                INDEX idx_feedback_session (session_id),
                INDEX idx_feedback_type (type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息级用户反馈（点赞/点踩）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("[JdbcFeedbackStore] 反馈表已就绪: cw_message_feedback");
        } catch (Exception e) {
            log.error("[JdbcFeedbackStore] ensureTable failed, errorCode={}", "FEEDBACK-STORE-DDL-FAIL", e);
        }
    }
}
