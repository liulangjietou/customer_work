package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 一条超限命中记录（谁、在哪、因为什么被限）。
 *
 * <p><b>为什么落库的是"命中"而不是"实时余额"</b>：余额在计数器里（进程内或 Redis），后台是另一个进程，
 * 想看余额要么被迫依赖 Redis 模式、要么读到一个只属于某个副本的数。而运营真正要回答的问题是
 * "谁在刷、哪一档配紧了"——那是命中记录的形状，写入量也只有超限那一刻这一次。</p>
 *
 * @param id            主键（JDBC 实现回填）
 * @param tenantId      归属租户
 * @param subjectType   主体类型
 * @param subjectId     主体标识（API Key 已是指纹，不含明文）
 * @param levelCode     判定所依据的等级
 * @param limitKind     触顶维度
 * @param used          触顶时的已用量
 * @param limitValue    触顶时的上限
 * @param windowSeconds 滚动窗口长度
 * @param action        当时的处置（BLOCK 真拦了 / WARN 只记录）
 * @param resource      触发位置（HTTP 路径或 {@code ws:chat}），排障时用来定位是哪条链路
 * @param createdAtMs   命中时刻
 * @author owlzhangfq@gmail.com
 */
public record SubjectQuotaHit(Long id,
                              String tenantId,
                              QuotaSubjectType subjectType,
                              String subjectId,
                              String levelCode,
                              SubjectQuotaDecision.LimitKind limitKind,
                              long used,
                              long limitValue,
                              int windowSeconds,
                              SubjectExceedAction action,
                              String resource,
                              long createdAtMs) {

    /** 由判定结果构造命中记录（时刻取当下）。 */
    public static SubjectQuotaHit of(String tenantId, QuotaSubject subject,
                                     SubjectQuotaDecision decision, String resource) {
        return new SubjectQuotaHit(null, tenantId, subject.type(), subject.id(),
            decision.levelCode(), decision.kind(), decision.used(), decision.limit(),
            decision.windowSeconds(), decision.action(), resource, System.currentTimeMillis());
    }
}
