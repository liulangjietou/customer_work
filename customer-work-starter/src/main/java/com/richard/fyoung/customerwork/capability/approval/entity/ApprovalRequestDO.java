package com.richard.fyoung.customerwork.capability.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 审批工单持久化对象（贫血数据袋，仅承载 {@code cw_approval} 表的行映射）。
 *
 * <p>与领域对象 {@code ApprovalRequest}（充血，含状态机）职责分离：DO 只做 MyBatis 读写，不含任何领域方法。
 * 枚举字段以 {@code String}（枚举 name）落库，时间/次数用原始类型，null 列回读为 0（等同旧
 * {@code ResultSet#getLong} 语义）。字段驼峰经 {@code mapUnderscoreToCamelCase} 自动映射到下划线列名。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_approval")
public class ApprovalRequestDO {

    /** 审批单号（应用赋值，非自增）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 审批类型枚举 name（REFUND 等）。 */
    private String type;

    /** 关联会话。 */
    private String sessionId;

    /** 关联订单号。 */
    private String orderId;

    /** 涉及金额。 */
    private String amount;

    /** 诉求原因。 */
    private String reason;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;

    /** 状态枚举 name：PENDING/APPROVED/DENIED。 */
    private String status;

    /** 决策操作员。 */
    private String operator;

    /** 决策备注。 */
    private String decisionNote;

    /** 决策时间戳（毫秒）。 */
    private long decidedAtMs;

    /** 下游执行状态枚举 name。 */
    private String executionStatus;

    /** 下游执行失败原因。 */
    private String executionFailureReason;

    /** 下游执行尝试次数。 */
    private int executionAttempts;
}
