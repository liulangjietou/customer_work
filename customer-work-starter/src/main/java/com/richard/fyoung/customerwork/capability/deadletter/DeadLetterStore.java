package com.richard.fyoung.customerwork.capability.deadletter;

import java.util.List;
import java.util.Optional;

/**
 * 死信存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryDeadLetterStore}；{@code dead-letter.store-mode=jdbc}
 * 落 {@code cw_dead_letter} 表。</p>
 *
 * <p><b>生产必须落库</b>：死信的全部意义是"进程/实例挂了之后这笔还能补回来"，
 * 存在进程内存里，恰恰在最需要它的那次故障中一起没了。</p>
 * @author owlzhangfq@gmail.com
 */
public interface DeadLetterStore {

    /** 保存（新建或覆盖）一条死信。 */
    void save(DeadLetter letter);

    /** 按 ID 查找。 */
    Optional<DeadLetter> find(String id);

    /** 取到期可重投的死信（PENDING 且 nextRetryAt <= now），最多 limit 条。 */
    List<DeadLetter> findDue(long nowMs, int limit);

    /** 按状态查询（运营列表用），时间倒序。 */
    List<DeadLetter> findByStatus(DeadLetterStatus status, int limit);

    /** 按状态计数（"待重投 N 条 / 已放弃 N 条"角标）。 */
    long count(DeadLetterStatus status);
}
