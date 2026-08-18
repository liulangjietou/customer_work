package com.richard.fyoung.customerwork.data.ticket;

import com.richard.fyoung.customerwork.core.common.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * 工单存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryTicketStore}（进程内，离线可测）；生产按 {@code customer-work.ticket.store-mode=jdbc}
 * 切换为 {@link MybatisTicketStore}（跨实例共享、重启不丢）。下游亦可声明同类型 Bean 整体覆盖。</p>
 *
 * <p>语义约定：{@link #save} 为新建、{@link #update} 为状态变更后持久化；{@link #appendEvent} 追加不可变
 * 审计事件并回填自增主键；{@link #findActiveBySession} 返回该会话下"非 CLOSED 且非 RESOLVED"的最新一张工单。</p>
 * @author owlzhangfq@gmail.com
 */
public interface TicketStore {

    /** 新建工单。 */
    void save(Ticket ticket);

    /** 状态变更后持久化最新状态。 */
    void update(Ticket ticket);

    /** 按工单号查找。 */
    Optional<Ticket> find(String id);

    /** 查该会话的活跃工单（非 CLOSED 且非 RESOLVED 的最新一张，用于建单幂等与转人工定位）。 */
    Optional<Ticket> findActiveBySession(String sessionId);

    /** 查该会话最新一张工单（包含终态，用于会话资源归属校验）。 */
    Optional<Ticket> findBySession(String sessionId);

    /** 查该用户的活跃工单（非 CLOSED 且非 RESOLVED 的最新一张，用于用户级唯一活跃会话去重）。 */
    Optional<Ticket> findActiveByUser(String userId);

    /** 分页查询（多维过滤 + 按 updatedAtMs 倒序）。 */
    PageResult<Ticket> findPage(TicketQuery query);

    /** 按状态查全部（SLA 巡检用）。 */
    List<Ticket> findByStatus(TicketStatus status);

    /** 追加一条工单事件（回填自增主键）。 */
    TicketEvent appendEvent(TicketEvent event);

    /** 查工单的全部事件（按发生顺序）。 */
    List<TicketEvent> findEvents(String ticketId);

    /**
     * 原子抢单：仅当工单当前为 WAITING_AGENT 时置为 PROCESSING 并绑定坐席，返回是否抢单成功。
     *
     * <p>用于并发抢单——多个坐席同时接同一张单，只有一个能成功（另一个得到 {@code false}）。
     * 默认实现基于本地 {@code synchronized} 提供同语义（供内存实现使用）；{@link MybatisTicketStore}
     * 覆盖为条件 UPDATE 的行数判定，跨实例并发安全。</p>
     *
     * @return true 抢单成功；false 已被他人抢走或工单不处于 WAITING_AGENT
     */
    default boolean claimAtomically(String id, String agentId, long nowMs) {
        synchronized (this) {
            Optional<Ticket> found = find(id);
            if (found.isEmpty() || found.get().getStatus() != TicketStatus.WAITING_AGENT) {
                return false;
            }
            Ticket ticket = found.get();
            ticket.claim(agentId);
            update(ticket);
            return true;
        }
    }
}
