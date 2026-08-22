package com.richard.fyoung.customerwork.core.memory;

import java.util.List;

/**
 * 事实日志 SPI（对应实战「三层记忆体系」的第三层：只追加事实日志）。
 *
 * <p>三层记忆体系：</p>
 * <ol>
 *   <li><b>L1 上下文内对话</b>：会话级短期记忆（{@code InMemoryMemory} / {@code AutoContextMemory}）；</li>
 *   <li><b>L2 长期记忆</b>：可语义召回的跨会话记忆（{@link LongTermMemoryStore}）；</li>
 *   <li><b>L3 事实日志</b>：本接口——常规业务链路只追加、不改写的事实流水，用于合规审计与
 *       “数据飞轮”回放；数据主体依法撤回或删除时，由治理链路按主体分区擦除。</li>
 * </ol>
 *
 * <p>唯一实现是 {@link MybatisFactLog}（落 {@code cw_fact_log} 表，多副本共享同一份流水），由
 * {@link FactLogConfig} 装配；持久化环境不可用时兜底 {@link NoOpFactLog}。刻意不提供文件形态——
 * 落在单机磁盘上的事实日志多副本各看各的、容器销毁即丢，没有审计价值。</p>
 *
 * <p>读取与追加是旁路能力；隐私擦除是合规主链路，失败必须向上抛出并进入重试/人工处置，
 * 不能返回虚假的“已删除”。</p>
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

    /**
     * 数据主体访问/导出使用的严格读取。实现必须在读取失败时抛出，不能用空列表伪装“没有数据”。
     */
    default List<String> readForSubjectAccess(String scopeId, int limit) {
        throw new UnsupportedOperationException("fact log subject access is not supported");
    }

    /** 隐私撤回时擦除指定主体事实；实现不支持时必须显式失败，不能假装已删除。 */
    default void erase(String scopeId) {
        throw new UnsupportedOperationException("fact log erasure is not supported");
    }
}
