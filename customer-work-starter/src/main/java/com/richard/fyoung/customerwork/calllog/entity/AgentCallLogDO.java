package com.richard.fyoung.customerwork.calllog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体调用主记录表 {@code cw_agent_call_log} 的持久化对象（贫血 DO）。
 *
 * <p>主键 {@code id} 自增（{@link IdType#AUTO}），保存后回填。{@code sessionType} 以枚举名字符串存储；
 * {@code startTime}/{@code endTime} 为毫秒时间戳（沿用本项目 *_ms 约定，趋势聚合用 {@code FROM_UNIXTIME}
 * 转换）；四类分段耗时冗余存储，报表免关联明细即可出各段汇总。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_agent_call_log")
public class AgentCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestId;
    private String userId;
    private String username;
    private String agentCode;
    private String agentName;
    private String sessionId;
    private String sessionType;
    private String question;
    private String answer;
    private Long startTime;
    private Long endTime;
    private Long durationMs;
    private Long modelMs;
    private Long toolMs;
    private Long mcpMs;
    private Long skillMs;
    private Integer segmentCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    /** 命中缓存的输入 token（inputTokens 的子集，不计入 totalTokens）。 */
    private Long cachedTokens;
    /** 各 MODEL 段模型自报耗时之和（毫秒），与实测 modelMs 之差即网络/排队开销。 */
    private Long modelReportedMs;
    private Boolean success;
    private String errorMsg;
}
