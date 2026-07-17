package com.richard.fyoung.customerwork.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计轨迹表 {@code cw_audit_log} 的持久化对象（贫血 DO）。
 *
 * <p>主键 {@code id} 为自增列（{@link IdType#AUTO}）。{@code created_at} 列为 SQL {@code TIMESTAMP}，
 * DO 侧用 {@link LocalDateTime} 承载（与 epochMilli 的互转由 Store 负责）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_audit_log")
public class AuditLogDO {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventType;
    private String agentName;
    private String eventData;
    private LocalDateTime createdAt;
}
