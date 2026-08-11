package com.richard.fyoung.customerwork.data.calllog;

import java.util.List;

/**
 * 智能体调用日志存储 SPI（第 9 个持久化扩展点）。
 *
 * <p>默认 {@link InMemoryAgentCallLogStore}；生产按 {@code customer-work.call-log.store-mode=jdbc}
 * 切换为 {@link MybatisAgentCallLogStore}（主表 {@code cw_agent_call_log} + 明细表
 * {@code cw_agent_call_segment}）。写入一次落主表与全部分段；查询提供分页 / 明细 / 汇总 / 趋势。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AgentCallLogStore {

    /** 保存一次调用（主表 + 明细段），返回回填了自增主键的主记录（明细段不回带）。 */
    AgentCallRecord save(AgentCallRecord record);

    /** 分页条件查询主记录（不含明细段），按 id 倒序（最新在前）。 */
    List<AgentCallRecord> findPage(AgentCallLogQuery query);

    /** 符合条件的主记录总数（配合 {@link #findPage} 做分页总数）。 */
    long count(AgentCallLogQuery query);

    /** 按主记录 id 查其全部分段明细（按 seq 升序）。 */
    List<AgentCallSegment> findSegments(long callLogId);

    /** 删除一条调用（主记录 + 其分段明细），返回是否删到主记录。 */
    boolean delete(long id);

    /** 汇总统计（总数 / 平均总耗时 / 最大总耗时 / 各段平均耗时）。 */
    AgentCallLogSummary summary(AgentCallLogQuery query);

    /** 按天 / 小时的趋势聚合（每桶：调用数 + 平均总耗时），按桶标签升序。 */
    List<AgentCallTrendPoint> trend(AgentCallLogQuery query, TrendGranularity granularity);
}
