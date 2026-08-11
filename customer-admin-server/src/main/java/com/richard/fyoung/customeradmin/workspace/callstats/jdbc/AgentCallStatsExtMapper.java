package com.richard.fyoung.customeradmin.workspace.callstats.jdbc;

import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallSummaryDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * admin 侧智能体调用统计扩展 Mapper（纯手写 XML，非 MyBatis-Plus BaseMapper）。
 *
 * <p><b>为何 admin 自带一套读侧 Mapper</b>：starter 的 {@code AgentCallLogMapper} 读侧不支持
 * {@code session_type} 过滤，且 trend 只出 avgDurationMs；而前端契约要求 page/summary/trend 能按
 * CHAT/VIBE_CODING 筛、trend 还要各段平均耗时。故此处对同两张表（{@code cw_agent_call_log} /
 * {@code cw_agent_call_segment}）另写一套读 SQL 补齐差异，starter 的 Mapper 仍复用于写入（insert）、
 * 按 id 取主记录（selectById）、明细段与删除，不重复造持久层。</p>
 *
 * <p>刻意放在 {@code .jdbc} 包（而非 {@code .mapper}）：admin 主 {@code @MapperScan("...**.mapper")}
 * 不扫到本接口，本接口只由 callstats 专用 {@code SqlSessionFactory} 注册，避免污染主 MyBatis 环境。
 * XML 在 {@code classpath:/callstats/AgentCallStatsExtMapper.xml}（避开主 MP 默认的
 * {@code classpath*:/mapper/**} 扫描）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AgentCallStatsExtMapper {

    /** 分页条件查询主记录（id 倒序，最新在前），复用 starter 的 {@link AgentCallLogDO} 承载列。 */
    List<AgentCallLogDO> findPage(@Param("q") AgentCallStatsQueryParam query);

    /** 符合条件的主记录总数。 */
    long countBy(@Param("q") AgentCallStatsQueryParam query);

    /** 汇总统计（总数/平均总耗时/最大总耗时/各段平均），复用 starter 的 {@link AgentCallSummaryDO}。 */
    AgentCallSummaryDO summary(@Param("q") AgentCallStatsQueryParam query);

    /** 趋势聚合：{@code dateFormat} 为 MySQL DATE_FORMAT 格式串（按天/小时），含各段平均耗时。 */
    List<AgentCallStatsTrendRow> trend(@Param("q") AgentCallStatsQueryParam query,
                                       @Param("dateFormat") String dateFormat);
}
