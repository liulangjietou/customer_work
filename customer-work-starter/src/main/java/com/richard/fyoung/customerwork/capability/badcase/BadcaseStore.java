package com.richard.fyoung.customerwork.capability.badcase;

import java.util.List;
import java.util.Optional;

/**
 * badcase 存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryBadcaseStore}（进程内，离线可测）；{@code badcase.store-mode=jdbc}
 * 落 {@code cw_badcase} 表。生产必须落库——badcase 的价值在于"攒够一批再集中筛"，
 * 进程内存储会让每次重启都把待筛队列清零。</p>
 *
 * <p>{@link #save} 是按 {@code id} 的 upsert：新建与状态流转后的回写共用一个入口。</p>
 * @author owlzhangfq@gmail.com
 */
public interface BadcaseStore {

    /** 保存（新建或覆盖）一条 badcase。 */
    void save(Badcase badcase);

    /** 按 ID 查找。 */
    Optional<Badcase> find(String id);

    /** 按条件查询，时间倒序（最新在前）。 */
    List<Badcase> query(BadcaseQuery query);

    /** 按条件计数（分页总数与"待筛 N 条"角标共用）。 */
    long count(BadcaseStatus status, BadcaseSource source);
}
