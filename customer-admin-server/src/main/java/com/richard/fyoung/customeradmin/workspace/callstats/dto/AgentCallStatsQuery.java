package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import lombok.Data;

/**
 * 智能体调用耗时统计查询条件（page / summary / trend 共用）。全部过滤项可空，空即不约束该维度。
 *
 * <p>{@code startTime}/{@code endTime} 为 {@code "yyyy-MM-dd HH:mm:ss"} 文本，服务层转毫秒时间戳按
 * 主表 {@code start_time} 过滤；{@code source} 决定路由到 ADMIN 本库还是 APP 客服端库；
 * {@code granularity} 仅 trend 用（day/hour）。Spring MVC 按同名请求参数绑定。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallStatsQuery {

    /** 数据源：ADMIN（默认）/ APP。 */
    private String source;

    /** 用户名（精确匹配）。 */
    private String username;

    /** 智能体编码（精确匹配）。 */
    private String agentCode;

    /** 会话类型 CHAT / VIBE_CODING（精确匹配）。 */
    private String sessionType;

    /** 请求ID（精确匹配）。 */
    private String requestId;

    /** 会话ID（精确匹配）。 */
    private String sessionId;

    /** W3C trace-id（精确匹配）。 */
    private String traceId;

    /** 已应用的运行配置发布修订（精确匹配）。 */
    private String runtimeRevision;

    /** 在线实验 ID 与实验臂（CONTROL/TREATMENT），用于曝光追溯。 */
    private Long experimentId;
    private String experimentArm;

    /** 起始时间下界（含），格式 {@code yyyy-MM-dd HH:mm:ss}。 */
    private String startTime;

    /** 起始时间上界（含），格式 {@code yyyy-MM-dd HH:mm:ss}。 */
    private String endTime;

    /** 趋势聚合粒度：day（默认）/ hour，仅 trend 接口使用。 */
    private String granularity;

    /** 页码（从 1 起），仅 page 接口使用。 */
    private Integer pageNum = 1;

    /** 每页条数，仅 page 接口使用。 */
    private Integer pageSize = 10;
}
