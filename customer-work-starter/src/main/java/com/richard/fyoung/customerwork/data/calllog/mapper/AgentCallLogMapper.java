package com.richard.fyoung.customerwork.data.calllog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallSummaryDO;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallTrendDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 智能体调用主记录 Mapper。写入走 {@link BaseMapper#insert}（AUTO 主键回填）；分页/汇总/趋势查询
 * 在 {@code AgentCallLogMapper.xml} 中手写（动态条件、聚合、按 {@code FROM_UNIXTIME} 分桶）。
 * @author owlzhangfq@gmail.com
 */
public interface AgentCallLogMapper extends BaseMapper<AgentCallLogDO> {

    /** 分页条件查询（id 倒序，最新在前）。 */
    List<AgentCallLogDO> findPage(@Param("q") AgentCallLogQueryParam query);

    /** 符合条件的总数。 */
    long countBy(@Param("q") AgentCallLogQueryParam query);

    /** 汇总统计（总数 / 平均总耗时 / 最大总耗时 / 各段平均）。 */
    AgentCallSummaryDO summary(@Param("q") AgentCallLogQueryParam query);

    /** 趋势聚合：{@code dateFormat} 为 MySQL DATE_FORMAT 格式串（按天/小时）。 */
    List<AgentCallTrendDO> trend(@Param("q") AgentCallLogQueryParam query,
                                 @Param("dateFormat") String dateFormat);
}
