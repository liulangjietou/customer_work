package com.richard.fyoung.customeradmin.workspace.chat.store;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessagePhase;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatReceipt;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatTerminal;
import com.richard.fyoung.customeradmin.workspace.chat.entity.WorkspaceMessageScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Admin 独立受理记录：每条语句显式限定完整归属，插入独立提交后才允许业务执行。 */
@Repository
public class WorkspaceMessageReceiptStore {
    private static final String OWNED = "CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND owner_user_id=? "
        + "AND CAST(agent_code AS BINARY)=CAST(? AS BINARY) AND CAST(session_id AS BINARY)=CAST(? AS BINARY) "
        + "AND channel=? AND client_message_id=?";
    private final JdbcTemplate jdbc;

    public WorkspaceMessageReceiptStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 唯一键决定唯一执行权；只捕获确实的重复键，数据库写故障必须向上传播。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accept(WorkspaceMessageScope scope, String fingerprint, long now) {
        try {
            jdbc.update("INSERT INTO ai_workspace_message_receipt(tenant_id,owner_user_id,agent_code,session_id,channel,"
                    + "client_message_id,input_hash,accepted_at_ms) VALUES(?,?,?,?,?,?,?,?)",
                scope.tenantId(), scope.ownerId(), scope.agentCode(), scope.sessionId(), scope.channel(),
                scope.clientMessageId(), fingerprint, now);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 重复消息必须与原资源、模式、正文和附件完全相符；冲突不能重新执行。 */
    public ChatReceipt require(WorkspaceMessageScope scope, String fingerprint) {
        return jdbc.query("SELECT * FROM ai_workspace_message_receipt WHERE " + OWNED + " AND input_hash=?",
                (row, index) -> read(row), scope.tenantId(), scope.ownerId(), scope.agentCode(), scope.sessionId(),
                scope.channel(), scope.clientMessageId(), fingerprint).stream().findFirst()
            .orElseThrow(() -> new BizException(ResultCode.WORKSPACE_MESSAGE_CONFLICT));
    }

    /** 查询无副作用；未知标识、别人的标识和不同会话标识均返回空。 */
    public Optional<ChatReceipt> find(WorkspaceMessageScope scope) {
        return jdbc.query("SELECT * FROM ai_workspace_message_receipt WHERE " + OWNED,
                (row, index) -> read(row), scope.tenantId(), scope.ownerId(), scope.agentCode(), scope.sessionId(),
                scope.channel(), scope.clientMessageId()).stream().findFirst();
    }

    /** 首个记录的终态获胜；取消收尾不能把已保存的权威终态覆盖成未知。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(WorkspaceMessageScope scope, ChatTerminal terminal) {
        jdbc.update("UPDATE ai_workspace_message_receipt SET terminal_phase=?,turn_id=?,message_id=?,finish_reason=?,"
                + "history_saved=?,artifacts_saved=?,knowledge_sources_saved=?,error_message=? WHERE " + OWNED
                + " AND terminal_phase IS NULL",
            terminal.phase().name(), terminal.turnId(), terminal.messageId(), terminal.finishReason(),
            terminal.historySaved(), terminal.artifactsSaved(), terminal.knowledgeSourcesSaved(), terminal.error(),
            scope.tenantId(), scope.ownerId(), scope.agentCode(), scope.sessionId(), scope.channel(), scope.clientMessageId());
    }

    private ChatReceipt read(ResultSet row) throws SQLException {
        String phase = row.getString("terminal_phase");
        ChatTerminal terminal = phase == null ? null : new ChatTerminal(row.getString("turn_id"), row.getString("message_id"),
            ChatMessagePhase.valueOf(phase), row.getString("finish_reason"), row.getBoolean("history_saved"),
            nullableBoolean(row, "artifacts_saved"), row.getString("error_message"), nullableBoolean(row, "knowledge_sources_saved"));
        return new ChatReceipt(row.getString("client_message_id"), row.getLong("accepted_at_ms"), terminal);
    }

    private Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
    }
}
