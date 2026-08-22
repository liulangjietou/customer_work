package com.richard.fyoung.customeradmin.workspace.callstats.jdbc;

import lombok.Data;

/**
 * 调用统计查询的 XML 传参对象（page / count / summary / trend 共用）。
 *
 * <p>相比 starter 的 {@code AgentCallLogQueryParam} 多出 {@code sessionType} 过滤维度——starter 的读侧
 * 契约不含会话类型过滤，而前端契约需要按 CHAT/VIBE_CODING 筛，故 admin 侧自带一套 ext Mapper 覆盖
 * page/summary/trend（trend 还额外聚合各段平均耗时），starter 的 Mapper 仅复用写入/明细/删除/按 id 取。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsQueryParam {

    private String username;
    private String agentCode;
    private String sessionType;
    private String requestId;
    private String sessionId;
    private String traceId;
    private String runtimeRevision;
    private Long experimentId;
    private String experimentArm;
    private Long startFromMs;
    private Long startToMs;
    private int offset;
    private int limit;
}
