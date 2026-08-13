package com.richard.fyoung.customerwork.core.memory;

import java.util.List;

/**
 * 事实日志 SPI（对应实战「三层记忆体系」的第三层：只追加事实日志）。
 *
 * <p>三层记忆体系：</p>
 * <ol>
 *   <li><b>L1 上下文内对话</b>：会话级短期记忆（{@code InMemoryMemory} / {@code AutoContextMemory}）；</li>
 *   <li><b>L2 长期记忆</b>：可语义召回的跨会话记忆（{@link LongTermMemoryStore}）；</li>
 *   <li><b>L3 事实日志</b>：本接口——只追加、不可变、可审计的事实流水，用于合规审计与"数据飞轮"回放，
 *       永不被压缩或改写。</li>
 * </ol>
 *
 * <p>两种实现，由 {@link FactLogConfig} 按 {@code customer-work.fact-log.store-mode} 选型：</p>
 * <ul>
 *   <li>{@code jdbc}（默认）：{@link MybatisFactLog}，落 {@code cw_fact_log} 表，多副本共享同一份流水；</li>
 *   <li>{@code file}：{@link FileFactLog}，按分区落盘为 JSONL（含大小轮转），适合不便建表的部署场景。</li>
 * </ul>
 *
 * <p>三个方法都不抛异常：事实日志是旁路能力，写失败退化为"这条没记上"，不该打断对话主链路。</p>
 * @author owlzhangfq@gmail.com
 */
public interface FactLog {

    /** 追加一条事实。空白忽略；写入失败不影响主链路。 */
    void append(String scopeId, String fact);

    /** 读取某分区的全部事实（按写入顺序）。 */
    List<String> read(String scopeId);

    /**
     * 读取某分区的全部事实记录（含时间戳，按写入顺序），供按时间窗聚合统计使用。
     * 与 {@link #read} 相互独立、互不影响。
     */
    List<FactRecord> readRecords(String scopeId);
}
