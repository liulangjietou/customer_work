package com.richard.fyoung.customerwork.data.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工单主表 {@code cw_ticket} 的持久化对象（贫血 DO，仅承载列映射）。
 *
 * <p>主键 {@code id} 为应用赋值的工单号（{@link IdType#INPUT}）。分类 / 优先级 / 状态在库中以枚举名字符串
 * 存储，DO 侧统一用 {@link String} 承载，领域对象 {@code Ticket} 的枚举与之互转由 Store 负责。
 * 驼峰字段自动映射下划线列（{@code mapUnderscoreToCamelCase} 已开）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_ticket")
public class TicketDO {

    /** 工单号（应用赋值字符串主键）。 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String userId;
    private String title;
    private String category;
    private String priority;
    private String status;
    private String assignee;
    private String handoffReason;
    private String resolveNote;
    private String routingCategory;
    private String requiredSkill;
    private String routingPriority;
    private String emotion;
    private String suggestedAssignees;
    private Integer reopenCount;
    private Long createdAtMs;
    private Long updatedAtMs;
    private Long handoffAtMs;
    private Long claimedAtMs;
    private Long resolvedAtMs;
    private Long closedAtMs;
    /** 用户最后活跃时间戳（毫秒）：历史行可能为 NULL，读取时由 Store 用 updated_at_ms 兜底。 */
    private Long lastUserActiveAtMs;
}
