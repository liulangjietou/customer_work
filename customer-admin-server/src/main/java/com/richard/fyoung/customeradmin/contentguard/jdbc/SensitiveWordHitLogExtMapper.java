package com.richard.fyoung.customeradmin.contentguard.jdbc;

import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordHitLogEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 命中日志读侧扩展 Mapper（纯手写 XML）：看板要的分页、汇总、趋势、Top 词全在这里。
 *
 * <p>聚合放 SQL 而不是拉回内存算——命中日志是持续增长的流水表，拉全量进程内聚合迟早撑爆；
 * 且 MySQL 对这几个按索引列的分组聚合本来就比应用层快。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordHitLogExtMapper {

    /** 多条件分页查询（id 倒序，最新在前）。 */
    List<SensitiveWordHitLogEntity> findPage(@Param("q") SensitiveWordHitLogQueryParam query);

    /** 符合条件的总数。 */
    long countBy(@Param("q") SensitiveWordHitLogQueryParam query);

    /** 按处置动作分组计数。 */
    List<ContentGuardCountRow> countByAction(@Param("q") SensitiveWordHitLogQueryParam query);

    /** 按命中方向分组计数。 */
    List<ContentGuardCountRow> countByDirection(@Param("q") SensitiveWordHitLogQueryParam query);

    /**
     * 命中最多的词 Top N。
     *
     * <p>按 {@code words} 整串分组：一次命中可能含多个词，严格拆分需要行转列，对看板"哪些词最常被触发"
     * 这个诉求来说收益不抵复杂度——多词命中本身占比很低，且整串分组同样能把高频组合暴露出来。</p>
     */
    List<ContentGuardCountRow> topWords(@Param("q") SensitiveWordHitLogQueryParam query, @Param("topN") int topN);

    /** 按时间粒度聚合的趋势（{@code dateFormat} 为 MySQL DATE_FORMAT 格式串，按天/按小时）。 */
    List<ContentGuardCountRow> trend(@Param("q") SensitiveWordHitLogQueryParam query,
                                     @Param("dateFormat") String dateFormat);
}
