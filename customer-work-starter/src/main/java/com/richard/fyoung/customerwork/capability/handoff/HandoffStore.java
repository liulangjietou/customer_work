package com.richard.fyoung.customerwork.capability.handoff;

import java.util.List;
import java.util.Optional;

/**
 * 历史人机切换工单存储 SPI（迁移兼容扩展点）。
 *
 * <p>P1-03 起生产不再注册该 SPI，权威状态统一由 {@code TicketService/cw_ticket} 承载。
 * 本接口与两种实现仅用于读取旧表和迁移回归测试，禁止新生产代码注入。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>{@link #save} 是 upsert（新建或更新已有工单）；</li>
 *   <li>{@link #update} 在工单状态变更后持久化最新状态（实现须保证线程安全）；</li>
 *   <li>返回的 {@link HandoffTicket} 须为同一引用或等值副本，调用方可安全操作其状态字段。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public interface HandoffStore {

    /** 保存（新建或覆盖）一张工单。 */
    void save(HandoffTicket ticket);

    /** 按 ID 查找工单。 */
    Optional<HandoffTicket> find(String id);

    /** 全部工单（含已结案）。 */
    List<HandoffTicket> findAll();

    /** 按状态过滤（如只看 PENDING 待接单）。 */
    List<HandoffTicket> findByStatus(HandoffStatus status);

    /** 更新工单（状态变更后调用）。 */
    void update(HandoffTicket ticket);
}
