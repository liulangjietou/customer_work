package com.richard.fyoung.customerwork.handoff.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 人机切换工单持久化对象（贫血数据袋）：与 {@code cw_handoff_ticket} 表一一映射。
 *
 * <p>领域状态机与流转语义在 {@link com.richard.fyoung.customerwork.handoff.HandoffTicket} 上，
 * 本 DO 只承载列值、不含领域方法。{@code status} 以枚举名字符串落库，转换在 Store 层完成。
 * 驼峰字段由 MyBatis-Plus 下划线映射自动对应下划线列，无需 {@code @TableField}。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_handoff_ticket")
public class HandoffTicketDO {

    /** 工单号（应用赋值，非自增）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String reason;
    private Long createdAtMs;
    private String status;
    private String claimedBy;
    private Long claimedAtMs;
    private String resolutionNote;
    private Long resolvedAtMs;
}
