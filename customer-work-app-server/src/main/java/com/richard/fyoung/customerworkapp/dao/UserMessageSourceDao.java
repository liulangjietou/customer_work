package com.richard.fyoung.customerworkapp.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.chatlog.ChatAnswerEvidence;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalSource;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 客户原文入口的消息证据查询：归属与持久证据同次读取，不依赖可关闭的租户 SQL 插件。 */
@Repository
public class UserMessageSourceDao {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIND_OWNED_SOURCES = """
        SELECT m.message_id, m.session_id AS message_session_id, m.tenant_id AS message_tenant_id,
               m.ticket_id AS message_ticket_id, m.sender_type, m.answer_evidence,
               t.id AS ticket_id, t.session_id AS ticket_session_id, t.tenant_id AS ticket_tenant_id, t.user_id
        FROM cw_chat_message m
        JOIN cw_ticket t ON t.tenant_id = m.tenant_id AND t.session_id = m.session_id
            AND (m.ticket_id IS NULL OR m.ticket_id = t.id)
        WHERE m.tenant_id = ? AND t.tenant_id = ? AND t.user_id = ?
            AND m.session_id = ? AND m.message_id = ? AND m.sender_type = ?
        """;

    private final JdbcTemplate jdbc;

    public UserMessageSourceDao(@Qualifier("customerWorkDataSource") ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
    }

    /** 无归属记录返回 empty；已落库的旧消息无证据时返回空列表，存储故障不伪装成任一种空态。 */
    public Optional<List<KnowledgeRetrievalSource>> findOwnedSources(String tenantId, String userId,
                                                                    String sessionId, String messageId) {
        if (jdbc == null) {
            throw new DataAccessResourceFailureException("customer message storage unavailable");
        }
        return jdbc.query(FIND_OWNED_SOURCES, rows -> {
            while (rows.next()) {
                if (matchesIdentity(rows, tenantId, userId, sessionId, messageId)) {
                    return Optional.of(decodeSources(rows.getString("answer_evidence")));
                }
            }
            return Optional.empty();
        }, tenantId, tenantId, userId, sessionId, messageId, TicketActorType.BOT.name());
    }

    /** 数据库排序规则忽略大小写；租户沿用同租户语义，资源与用户 ID 必须精确相等。 */
    private boolean matchesIdentity(ResultSet row, String tenantId, String userId,
                                     String sessionId, String messageId) throws SQLException {
        String messageTicket = row.getString("message_ticket_id");
        return TenantContext.sameTenant(tenantId, row.getString("message_tenant_id"))
            && TenantContext.sameTenant(tenantId, row.getString("ticket_tenant_id"))
            && userId.equals(row.getString("user_id"))
            && sessionId.equals(row.getString("message_session_id"))
            && sessionId.equals(row.getString("ticket_session_id"))
            && messageId.equals(row.getString("message_id"))
            && TicketActorType.BOT.name().equals(row.getString("sender_type"))
            && (messageTicket == null || messageTicket.equals(row.getString("ticket_id")));
    }

    private List<KnowledgeRetrievalSource> decodeSources(String json) {
        if (json == null) return List.of();
        try {
            ChatAnswerEvidence evidence = JSON.readValue(json, ChatAnswerEvidence.class);
            return evidence == null ? List.of() : evidence.retrievalSources();
        } catch (JsonProcessingException error) {
            throw new DataRetrievalFailureException("cannot decode chat answer evidence", error);
        }
    }
}
