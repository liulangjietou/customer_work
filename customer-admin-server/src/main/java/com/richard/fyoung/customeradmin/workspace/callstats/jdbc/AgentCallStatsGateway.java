package com.richard.fyoung.customeradmin.workspace.callstats.jdbc;

import com.richard.fyoung.customerwork.calllog.AgentCallLogStore;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallSegmentMapper;

/**
 * 单个数据源（ADMIN 本库 / APP 客服端库）的调用统计读写门面：把该源的一套 Mapper 与 Store 收拢在一起。
 *
 * <p>{@code extMapper} 承接 page/summary/trend（含 sessionType 过滤 + 各段趋势）；starter 的
 * {@code logMapper} 提供按 id 取主记录（BaseMapper#selectById，供详情），{@code segmentMapper} 提供
 * 明细段查询；{@code store} 复用 starter 的删除（主记录 + 分段级联）与写入（save，仅 ADMIN 侧 Sink 用到）。</p>
 * @author owlzhangfq@gmail.com
 */
public class AgentCallStatsGateway {

    private final AgentCallStatsExtMapper extMapper;
    private final AgentCallLogMapper logMapper;
    private final AgentCallSegmentMapper segmentMapper;
    private final AgentCallLogStore store;

    public AgentCallStatsGateway(AgentCallStatsExtMapper extMapper, AgentCallLogMapper logMapper,
                                 AgentCallSegmentMapper segmentMapper, AgentCallLogStore store) {
        this.extMapper = extMapper;
        this.logMapper = logMapper;
        this.segmentMapper = segmentMapper;
        this.store = store;
    }

    public AgentCallStatsExtMapper extMapper() {
        return extMapper;
    }

    public AgentCallLogMapper logMapper() {
        return logMapper;
    }

    public AgentCallSegmentMapper segmentMapper() {
        return segmentMapper;
    }

    public AgentCallLogStore store() {
        return store;
    }
}
