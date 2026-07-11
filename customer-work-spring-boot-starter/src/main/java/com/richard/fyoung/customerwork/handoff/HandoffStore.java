package com.richard.fyoung.customerwork.handoff;

import java.util.List;
import java.util.Optional;

/**
 * 人机切换工单存储 SPI（持久化扩展点）。
 *
 * <p>默认由 {@link InMemoryHandoffStore} 提供进程内实现（离线可测、确定性）；
 * 生产可替换为 JDBC / Redis 等持久化实现（声明同类型 Bean 覆盖默认），保证工单重启不丢失——
 * 多实例部署下坐席在实例 A 接单、查询落到实例 B 也应能看到最新状态。</p>
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
