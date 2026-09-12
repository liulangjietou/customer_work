package com.richard.fyoung.customerworkapp.dao;

import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用户退款办理记录的数据库投影，只读取经过会话和订单归属约束的公开字段。 */
@Repository
public class UserRefundApprovalDao {
    private static final String OWNED_SESSION_SQL = "SELECT 1 FROM cw_ticket WHERE tenant_id = ? "
        + "AND CAST(session_id AS BINARY) = CAST(? AS BINARY) "
        + "AND CAST(user_id AS BINARY) = CAST(? AS BINARY) LIMIT 1";
    private static final String VISIBLE_APPROVALS_SQL = " FROM cw_approval a JOIN cw_order o "
        + "ON o.order_id = a.order_id AND o.tenant_id = a.tenant_id "
        + "WHERE a.tenant_id = ? AND CAST(a.session_id AS BINARY) = CAST(? AS BINARY) AND a.type = ? "
        + "AND CAST(o.user_id AS BINARY) = CAST(? AS BINARY) "
        + "AND EXISTS (SELECT 1 FROM cw_ticket t WHERE t.tenant_id = a.tenant_id "
        + "AND CAST(t.session_id AS BINARY) = CAST(a.session_id AS BINARY) "
        + "AND CAST(t.user_id AS BINARY) = CAST(o.user_id AS BINARY))";
    private static final String COUNT_SQL = "SELECT COUNT(*)" + VISIBLE_APPROVALS_SQL;
    private static final String PAGE_SQL = "SELECT a.id, a.order_id, a.amount, a.status, a.execution_status, "
        + "a.created_at_ms, a.decided_at_ms" + VISIBLE_APPROVALS_SQL
        + " ORDER BY a.created_at_ms DESC, a.id DESC LIMIT ? OFFSET ?";

    private final JdbcTemplate jdbc;

    public UserRefundApprovalDao(@Qualifier("customerWorkDataSource") ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
    }

    /** 缺少客服业务数据源时，入口应返回明确的不可用状态。 */
    public boolean isEnabled() { return jdbc != null; }

    /** 不依赖可关闭的 MyBatis 租户插件，复验会话根资源的真实租户和当前用户。 */
    public boolean hasOwnedSession(String sessionId, String userId) {
        return !jdbc.queryForList(OWNED_SESSION_SQL, Integer.class,
            TenantContext.require(), sessionId, userId).isEmpty();
    }

    /** 服务端分页；计数与数据页共享同一组权限条件，金额保持审批记录的原始字符串。 */
    public PageResult<RefundApprovalView> findPage(String sessionId, String userId, int page, int size) {
        String tenantId = TenantContext.require();
        long total = jdbc.queryForObject(COUNT_SQL, Long.class, tenantId, sessionId, ApprovalType.REFUND.name(), userId);
        long offset = ((long) page - 1) * size;
        var items = jdbc.query(PAGE_SQL, UserRefundApprovalDao::readView,
            tenantId, sessionId, ApprovalType.REFUND.name(), userId, size, offset);
        return new PageResult<>(total, items);
    }

    private static RefundApprovalView readView(ResultSet row, int rowNum) throws SQLException {
        return new RefundApprovalView(row.getString("id"), row.getString("order_id"), row.getString("amount"),
            row.getString("status"), row.getString("execution_status"), row.getLong("created_at_ms"),
            row.getObject("decided_at_ms", Long.class));
    }

    /** 用户可见的办理事实；审批和执行状态分别返回，不推断是否到账。 */
    public record RefundApprovalView(String id, String orderId, String amount, String approvalStatus,
                                     String executionStatus, long createdAtMs, Long decidedAtMs) { }
}
