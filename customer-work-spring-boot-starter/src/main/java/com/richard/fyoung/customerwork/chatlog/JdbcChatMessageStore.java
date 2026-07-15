package com.richard.fyoung.customerwork.chatlog;

import com.richard.fyoung.customerwork.ticket.TicketActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC 聊天消息存储（{@code customer-work.chat-log.store-mode=jdbc} 时启用）。
 *
 * <p>写入 {@code cw_chat_message} 表，自增主键经 {@code RETURN_GENERATED_KEYS} 回填。历史查询用
 * {@code ORDER BY id DESC LIMIT ?} 取最新一页后在内存翻转为升序（游标翻页）。手法对齐
 * {@code JdbcHandoffStore}：构造即幂等建表，异常一律 {@code catch(Exception)}。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcChatMessageStore implements ChatMessageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMessageStore.class);

    private static final String INSERT_SQL =
        "INSERT INTO cw_chat_message (message_id, session_id, ticket_id, sender_type, sender_id, "
        + "content, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS =
        "SELECT id, message_id, session_id, ticket_id, sender_type, sender_id, content, created_at_ms "
        + "FROM cw_chat_message";

    private final DataSource dataSource;

    public JdbcChatMessageStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public ChatMessage append(ChatMessage message) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, message.messageId());
            ps.setString(2, message.sessionId());
            ps.setString(3, message.ticketId());
            ps.setString(4, message.senderType().name());
            ps.setString(5, message.senderId());
            ps.setString(6, message.content());
            ps.setLong(7, message.createdAtMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0L;
                return message.withId(id);
            }
        } catch (Exception e) {
            log.error("chat message append failed, code={}, messageId={}",
                "CHATLOG-APPEND-FAIL", message.messageId(), e);
            throw new IllegalStateException("failed to append chat message: " + message.messageId(), e);
        }
    }

    @Override
    public List<ChatMessage> findBySession(String sessionId, Long beforeId, int limit) {
        return page("session_id", sessionId, beforeId, limit, "CHATLOG-FINDBYSESSION-FAIL");
    }

    @Override
    public List<ChatMessage> findByTicket(String ticketId, Long beforeId, int limit) {
        return page("ticket_id", ticketId, beforeId, limit, "CHATLOG-FINDBYTICKET-FAIL");
    }

    /** 游标翻页：DESC + LIMIT 取最新一页，读出后翻转为升序返回。 */
    private List<ChatMessage> page(String dimColumn, String dimValue, Long beforeId, int limit, String errorCode) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append(" WHERE ").append(dimColumn).append(" = ?");
        if (beforeId != null) {
            sql.append(" AND id < ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, dimValue);
            if (beforeId != null) {
                ps.setLong(idx++, beforeId);
            }
            ps.setInt(idx, Math.max(limit, 0));
            try (ResultSet rs = ps.executeQuery()) {
                List<ChatMessage> desc = new ArrayList<>();
                while (rs.next()) {
                    desc.add(mapRow(rs));
                }
                Collections.reverse(desc);
                return desc;
            }
        } catch (Exception e) {
            log.error("chat message query failed, code={}, dim={}, value={}", errorCode, dimColumn, dimValue, e);
            return List.of();
        }
    }

    private ChatMessage mapRow(ResultSet rs) throws SQLException {
        return new ChatMessage(
            rs.getLong("id"),
            rs.getString("message_id"),
            rs.getString("session_id"),
            rs.getString("ticket_id"),
            TicketActorType.valueOf(rs.getString("sender_type")),
            rs.getString("sender_id"),
            rs.getString("content"),
            rs.getLong("created_at_ms"));
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_chat_message (
                id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（游标翻页）',
                message_id VARCHAR(64) NOT NULL COMMENT '业务消息号 MSG-<uuid>',
                session_id VARCHAR(128) NOT NULL COMMENT '所属会话',
                ticket_id VARCHAR(64) COMMENT '关联工单号（可空）',
                sender_type VARCHAR(16) NOT NULL COMMENT '发送方类型 USER/BOT/AGENT/SYSTEM',
                sender_id VARCHAR(64) COMMENT '发送方标识（可空）',
                content TEXT NOT NULL COMMENT '消息内容',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                UNIQUE KEY uk_chat_message_id (message_id),
                INDEX idx_chat_session (session_id, id),
                INDEX idx_chat_ticket (ticket_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话/工单聊天消息留痕'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            log.info("chat message table ready: cw_chat_message");
        } catch (Exception e) {
            log.error("chat message ensureTable failed, code={}", "CHATLOG-DDL-FAIL", e);
        }
    }
}
