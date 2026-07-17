package com.richard.fyoung.customerwork.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工单事件轨迹表 {@code cw_ticket_event} 的持久化对象（贫血 DO）。
 *
 * <p>主键 {@code id} 为自增列（{@link IdType#AUTO}），追加后由 MyBatis 回填。事件类型 / 前后状态 /
 * 动作发起方类型在库中以枚举名字符串存储，DO 侧统一用 {@link String} 承载。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_ticket_event")
public class TicketEventDO {

    /** 事件自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String actorType;
    private String actorId;
    private String note;
    private Long createdAtMs;
}
