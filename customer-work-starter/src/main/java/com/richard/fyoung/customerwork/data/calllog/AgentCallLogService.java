package com.richard.fyoung.customerwork.data.calllog;

import java.util.List;

/**
 * 智能体调用日志查询服务：面向下游（admin-server 报表等）封装存储 SPI 的读侧能力。
 *
 * <p>写侧由 {@code AgentCallTimingMiddleware} → {@link AgentCallRecordSink} 异步完成，本服务只做查询：
 * 分页 / 明细段 / 汇总 / 趋势 / 删除。薄封装，便于下游注入使用，也隔离下游对具体 {@link AgentCallLogStore}
 * 实现的直接依赖。</p>
 * @author owlzhangfq@gmail.com
 */
public class AgentCallLogService {

    private final AgentCallLogStore store;

    public AgentCallLogService(AgentCallLogStore store) {
        this.store = store;
    }

    /** 分页条件查询主记录（不含明细段）。 */
    public List<AgentCallRecord> page(AgentCallLogQuery query) {
        return store.findPage(query);
    }

    /** 符合条件总数（配合分页）。 */
    public long count(AgentCallLogQuery query) {
        return store.count(query);
    }

    /** 按主记录 id 查分段明细（seq 升序）。 */
    public List<AgentCallSegment> segments(long callLogId) {
        return store.findSegments(callLogId);
    }

    /** 删除一条调用（主记录 + 分段）。 */
    public boolean delete(long id) {
        return store.delete(id);
    }

    /** 汇总统计。 */
    public AgentCallLogSummary summary(AgentCallLogQuery query) {
        return store.summary(query);
    }

    /** 按天 / 小时趋势聚合。 */
    public List<AgentCallTrendPoint> trend(AgentCallLogQuery query, TrendGranularity granularity) {
        return store.trend(query, granularity);
    }
}
