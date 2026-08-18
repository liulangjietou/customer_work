package com.richard.fyoung.customerwork.safety.subjectquota;

import java.util.List;

/**
 * 超限命中记录存储 SPI。
 *
 * <p>写入只发生在超限那一刻，正常流量不产生任何写——这是它敢于同步落库的前提。
 * 即便如此，调用方仍应异步提交（见 {@code SubjectQuotaGuard}），因为限流判定在响应式链路上，
 * 阻塞 IO 不能占用事件循环线程。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SubjectQuotaHitStore {

    /** 记录一次命中。实现须自行吞掉异常：记不下这条统计，不该让本就被拒的请求再抛一个错。 */
    void record(SubjectQuotaHit hit);

    /** 按租户查最近的命中明细（时间倒序）。 */
    List<SubjectQuotaHit> findRecent(String tenantId, long sinceMs, int limit);

    /** 按租户查指定区间内的命中排行（命中次数倒序），供"谁在刷"看板。 */
    List<SubjectQuotaHitRank> rank(String tenantId, long sinceMs, int limit);
}
