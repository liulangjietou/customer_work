package com.richard.fyoung.customerwork.ticket;

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
 * JDBC 工单存储（生产参考实现：{@code customer-work.ticket.store-mode=jdbc} 时启用）。
 *
 * <p>把工单主表 {@code cw_ticket} 与事件轨迹表 {@code cw_ticket_event} 结构化落库，保证重启 / 多实例
 * 部署下工单与操作轨迹不丢失。{@link #claimAtomically} 用条件 UPDATE 的影响行数判定并发抢单——
 * 只有一名坐席能把 WAITING_AGENT 置为 PROCESSING，跨实例并发安全（进程内 {@link InMemoryTicketStore}
 * 靠单机 synchronized，多实例无此保证）。</p>
 *
 * <p>由 {@link TicketConfig} 装配，复用 {@code session.mysql.*} 连接配置。建表 SQL 见
 * {@code mysql/schema.sql} 中的 {@code cw_ticket} / {@code cw_ticket_event} 表。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcTicketStore implements TicketStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTicketStore.class);

    private static final String TICKET_COLUMNS =
        "id, session_id, user_id, title, category, priority, status, assignee, handoff_reason, "
        + "resolve_note, reopen_count, created_at_ms, updated_at_ms, handoff_at_ms, claimed_at_ms, "
        + "resolved_at_ms, closed_at_ms";

    private static final String UPSERT_SQL =
        "INSERT INTO cw_ticket (" + TICKET_COLUMNS + ") "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE title=VALUES(title), category=VALUES(category), "
        + "priority=VALUES(priority), status=VALUES(status), assignee=VALUES(assignee), "
        + "handoff_reason=VALUES(handoff_reason), resolve_note=VALUES(resolve_note), "
        + "reopen_count=VALUES(reopen_count), updated_at_ms=VALUES(updated_at_ms), "
        + "handoff_at_ms=VALUES(handoff_at_ms), claimed_at_ms=VALUES(claimed_at_ms), "
        + "resolved_at_ms=VALUES(resolved_at_ms), closed_at_ms=VALUES(closed_at_ms)";

    private static final String SELECT_BASE = "SELECT " + TICKET_COLUMNS + " FROM cw_ticket";

    /** 并发抢单：仅当仍为 WAITING_AGENT 时置 PROCESSING（enum 名拼接，避免魔法字面量）。 */
    private static final String CLAIM_SQL =
        "UPDATE cw_ticket SET status='" + TicketStatus.PROCESSING.name() + "', assignee=?, "
        + "claimed_at_ms=?, updated_at_ms=? WHERE id=? AND status='" + TicketStatus.WAITING_AGENT.name() + "'";

    private static final String EVENT_COLUMNS =
        "ticket_id, event_type, from_status, to_status, actor_type, actor_id, note, created_at_ms";

    private static final String INSERT_EVENT_SQL =
        "INSERT INTO cw_ticket_event (" + EVENT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    public JdbcTicketStore(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public void save(Ticket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            bindTicket(ps, ticket);
            ps.executeUpdate();
        } catch (Exception e) {
            // 捕获 Exception：HikariPool 初始化异常是 RuntimeException，非 SQLException 子类，必须兜住
            log.error("ticket save failed, code={}, id={}", "TICKET-STORE-SAVE-FAIL", ticket.getId(), e);
            throw new IllegalStateException("failed to save ticket: " + ticket.getId(), e);
        }
    }

    @Override
    public void update(Ticket ticket) {
        save(ticket);
    }

    @Override
    public Optional<Ticket> find(String id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("ticket find failed, code={}, id={}", "TICKET-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Ticket> findActiveBySession(String sessionId) {
        String sql = SELECT_BASE + " WHERE session_id = ? AND status NOT IN ('"
            + TicketStatus.CLOSED.name() + "', '" + TicketStatus.RESOLVED.name() + "') "
            + "ORDER BY created_at_ms DESC LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("ticket findActiveBySession failed, code={}, session={}",
                "TICKET-STORE-FINDACTIVE-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public PageResult findPage(TicketQuery query) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(query, params);
        long total = count(where, params);
        if (total == 0) {
            return new PageResult(0, List.of());
        }
        String sql = SELECT_BASE + where + " ORDER BY updated_at_ms DESC LIMIT ? OFFSET ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = bindParams(ps, params);
            ps.setInt(idx++, query.normalizedPageSize());
            ps.setInt(idx, query.offset());
            try (ResultSet rs = ps.executeQuery()) {
                List<Ticket> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return new PageResult(total, list);
            }
        } catch (Exception e) {
            log.error("ticket findPage failed, code={}", "TICKET-STORE-FINDPAGE-FAIL", e);
            return new PageResult(0, List.of());
        }
    }

    @Override
    public List<Ticket> findByStatus(TicketStatus status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_BASE + " WHERE status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<Ticket> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        } catch (Exception e) {
            log.error("ticket findByStatus failed, code={}, status={}",
                "TICKET-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    @Override
    public TicketEvent appendEvent(TicketEvent event) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_EVENT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.ticketId());
            ps.setString(2, event.eventType().name());
            ps.setString(3, event.fromStatus() == null ? null : event.fromStatus().name());
            ps.setString(4, event.toStatus() == null ? null : event.toStatus().name());
            ps.setString(5, event.actorType().name());
            ps.setString(6, event.actorId());
            ps.setString(7, event.note());
            ps.setLong(8, event.createdAtMs());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0L;
                return event.withId(id);
            }
        } catch (Exception e) {
            log.error("ticket appendEvent failed, code={}, ticketId={}",
                "TICKET-STORE-APPENDEVENT-FAIL", event.ticketId(), e);
            throw new IllegalStateException("failed to append ticket event: " + event.ticketId(), e);
        }
    }

    @Override
    public List<TicketEvent> findEvents(String ticketId) {
        String sql = "SELECT id, " + EVENT_COLUMNS + " FROM cw_ticket_event WHERE ticket_id = ? ORDER BY id ASC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TicketEvent> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapEvent(rs));
                }
                return list;
            }
        } catch (Exception e) {
            log.error("ticket findEvents failed, code={}, ticketId={}",
                "TICKET-STORE-FINDEVENTS-FAIL", ticketId, e);
            return List.of();
        }
    }

    @Override
    public boolean claimAtomically(String id, String agentId, long nowMs) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(CLAIM_SQL)) {
            ps.setString(1, agentId);
            ps.setLong(2, nowMs);
            ps.setLong(3, nowMs);
            ps.setString(4, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            log.error("ticket claimAtomically failed, code={}, id={}", "TICKET-CLAIM-FAIL", id, e);
            return false;
        }
    }

    /** 绑定工单全字段（顺序与 {@link #TICKET_COLUMNS} 一致）。 */
    private void bindTicket(PreparedStatement ps, Ticket t) throws SQLException {
        ps.setString(1, t.getId());
        ps.setString(2, t.getSessionId());
        ps.setString(3, t.getUserId());
        ps.setString(4, t.getTitle());
        ps.setString(5, t.getCategory() == null ? null : t.getCategory().name());
        ps.setString(6, t.getPriority() == null ? null : t.getPriority().name());
        ps.setString(7, t.getStatus().name());
        ps.setString(8, t.getAssignee());
        ps.setString(9, t.getHandoffReason());
        ps.setString(10, t.getResolveNote());
        ps.setInt(11, t.getReopenCount());
        ps.setLong(12, t.getCreatedAtMs());
        ps.setLong(13, t.getUpdatedAtMs());
        ps.setLong(14, t.getHandoffAtMs());
        ps.setLong(15, t.getClaimedAtMs());
        ps.setLong(16, t.getResolvedAtMs());
        ps.setLong(17, t.getClosedAtMs());
    }

    /** 拼装分页动态 WHERE（各过滤项非 null 才拼），参数收集到 params 保持占位符顺序。 */
    private String buildWhere(TicketQuery query, List<Object> params) {
        StringBuilder sb = new StringBuilder();
        if (query.status() != null) {
            append(sb, "status = ?", params, query.status().name());
        }
        if (query.assignee() != null) {
            append(sb, "assignee = ?", params, query.assignee());
        }
        if (query.category() != null) {
            append(sb, "category = ?", params, query.category().name());
        }
        if (query.priority() != null) {
            append(sb, "priority = ?", params, query.priority().name());
        }
        if (query.userId() != null) {
            append(sb, "user_id = ?", params, query.userId());
        }
        return sb.length() == 0 ? "" : " WHERE " + sb;
    }

    private void append(StringBuilder sb, String clause, List<Object> params, Object value) {
        if (sb.length() > 0) {
            sb.append(" AND ");
        }
        sb.append(clause);
        params.add(value);
    }

    private long count(String where, List<Object> params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM cw_ticket" + where)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            log.error("ticket count failed, code={}", "TICKET-STORE-COUNT-FAIL", e);
            return 0L;
        }
    }

    /** 依次绑定字符串参数，返回下一个可用占位符索引。 */
    private int bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        int idx = 1;
        for (Object p : params) {
            ps.setString(idx++, String.valueOf(p));
        }
        return idx;
    }

    private Ticket mapRow(ResultSet rs) throws SQLException {
        return Ticket.reconstruct(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            enumOrNull(TicketCategory.class, rs.getString("category")),
            enumOrNull(TicketPriority.class, rs.getString("priority")),
            TicketStatus.valueOf(rs.getString("status")),
            rs.getString("assignee"),
            rs.getString("handoff_reason"),
            rs.getString("resolve_note"),
            rs.getInt("reopen_count"),
            rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"),
            rs.getLong("handoff_at_ms"),
            rs.getLong("claimed_at_ms"),
            rs.getLong("resolved_at_ms"),
            rs.getLong("closed_at_ms"));
    }

    private TicketEvent mapEvent(ResultSet rs) throws SQLException {
        return new TicketEvent(
            rs.getLong("id"),
            rs.getString("ticket_id"),
            TicketEventType.valueOf(rs.getString("event_type")),
            enumOrNull(TicketStatus.class, rs.getString("from_status")),
            enumOrNull(TicketStatus.class, rs.getString("to_status")),
            TicketActorType.valueOf(rs.getString("actor_type")),
            rs.getString("actor_id"),
            rs.getString("note"),
            rs.getLong("created_at_ms"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    /** 自动建表（幂等，表已存在则跳过）。 */
    private void ensureTable() {
        String ticketDdl = """
            CREATE TABLE IF NOT EXISTS cw_ticket (
                id VARCHAR(64) PRIMARY KEY COMMENT '工单号',
                session_id VARCHAR(128) NOT NULL COMMENT '关联会话',
                user_id VARCHAR(64) NOT NULL COMMENT '发起用户',
                title VARCHAR(255) COMMENT '工单标题',
                category VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '分类',
                priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '优先级',
                status VARCHAR(32) NOT NULL COMMENT '状态机状态',
                assignee VARCHAR(64) COMMENT '当前处理坐席',
                handoff_reason VARCHAR(255) COMMENT '转人工原因',
                resolve_note TEXT COMMENT '处理结论/备注',
                reopen_count INT NOT NULL DEFAULT 0 COMMENT '重开次数',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                updated_at_ms BIGINT NOT NULL COMMENT '更新时间戳（毫秒）',
                handoff_at_ms BIGINT DEFAULT 0 COMMENT '最近转人工时间戳（毫秒）',
                claimed_at_ms BIGINT DEFAULT 0 COMMENT '接单时间戳（毫秒）',
                resolved_at_ms BIGINT DEFAULT 0 COMMENT '解决时间戳（毫秒）',
                closed_at_ms BIGINT DEFAULT 0 COMMENT '关闭时间戳（毫秒）',
                INDEX idx_ticket_session (session_id),
                INDEX idx_ticket_user (user_id, created_at_ms),
                INDEX idx_ticket_status (status, updated_at_ms),
                INDEX idx_ticket_assignee (assignee, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单（完整生命周期状态机）'
            """;
        String eventDdl = """
            CREATE TABLE IF NOT EXISTS cw_ticket_event (
                id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增主键',
                ticket_id VARCHAR(64) NOT NULL COMMENT '所属工单号',
                event_type VARCHAR(32) NOT NULL COMMENT '事件类型',
                from_status VARCHAR(32) COMMENT '流转前状态',
                to_status VARCHAR(32) COMMENT '流转后状态',
                actor_type VARCHAR(16) NOT NULL COMMENT '动作发起方类型',
                actor_id VARCHAR(64) COMMENT '动作发起方标识',
                note VARCHAR(500) COMMENT '备注',
                created_at_ms BIGINT NOT NULL COMMENT '事件时间戳（毫秒）',
                INDEX idx_ticket_event (ticket_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单事件轨迹（不可变审计）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ticketDdl);
            stmt.execute(eventDdl);
            log.info("ticket tables ready: cw_ticket, cw_ticket_event");
        } catch (Exception e) {
            // 捕获 Exception 而非 SQLException：连接池首次取连接失败抛 RuntimeException（HikariPool 初始化）；
            // 建表失败不阻断 Bean 装配，后续读写各自重试并记录异常
            log.error("ticket ensureTable failed, code={}", "TICKET-STORE-DDL-FAIL", e);
        }
    }
}
