package com.richard.fyoung.customerwork.capability.routing;

import java.util.List;

/**
 * 坐席库存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemorySeatAgentStore}（进程内、带演示坐席种子，离线可测）；生产可切
 * {@link MybatisSeatAgentStore}（{@code customer-work.routing.seat-store-mode=jdbc}），或下游声明同类型 Bean 覆盖。
 * {@link SeatRoutingScorer} 通过本 SPI 拉取候选坐席全集打分。</p>
 *
 * <p>语义约定：{@link #findAll} 读失败降级<b>空集合</b>（fail-open：无候选 → 无推荐，坐席照常手动接单，
 * 与敏感词 fail-closed 相反）；{@link #save} 为 upsert（新建或更新坐席）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SeatAgentStore {

    /** 全部坐席（含离线；在线/负载过滤在打分器侧）。读失败降级空集合。 */
    List<SeatAgent> findAll();

    /** 保存（新建或更新）一个坐席。 */
    void save(SeatAgent seat);
}
