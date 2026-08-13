package com.richard.fyoung.customerwork.capability.csat;

import java.util.List;
import java.util.Optional;

/**
 * CSAT 调查存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryCsatStore}；{@code csat.store-mode=jdbc} 落 {@code cw_csat_survey} 表。
 * 生产必须落库：CSAT 是要按周、按月看趋势的运营指标，存在进程里等于没有。</p>
 *
 * <p>{@link #save} 是按 {@code sessionId} 的 upsert：邀请与评分共用一个写入口。</p>
 * @author owlzhangfq@gmail.com
 */
public interface CsatStore {

    /** 保存（新建或覆盖）一条调查记录。 */
    void save(CsatSurvey survey);

    /** 按会话查找。 */
    Optional<CsatSurvey> find(String sessionId);

    /** 按分区与时间窗查（统计用）；窗口以<b>邀请时间</b>为准。 */
    List<CsatSurvey> findByWindow(String scopeId, long startMs, long endMs);
}
