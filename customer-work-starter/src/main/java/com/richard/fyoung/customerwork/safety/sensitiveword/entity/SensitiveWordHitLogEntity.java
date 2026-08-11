package com.richard.fyoung.customerwork.safety.sensitiveword.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 敏感词命中日志持久化对象（贫血数据袋）：与 {@code cw_sensitive_word_hit_log} 表一一映射。
 *
 * <p>{@code words} / {@code categories} 以逗号分隔串落库（照 {@code cw_seat_agent.skills} 先例）——
 * 命中词是展示与聚合的维度，不需要独立成表做关联查询，一条命中天然就是一行流水。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_sensitive_word_hit_log")
public class SensitiveWordHitLogEntity {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 命中方向枚举名（INBOUND/OUTBOUND）。 */
    private String direction;

    /** 整体决策枚举名（BLOCK/MASK/REVIEW）。 */
    private String action;

    /** 命中词面，逗号分隔。 */
    private String words;

    /** 命中类目枚举名，逗号分隔、已去重。 */
    private String categories;

    /** 命中词个数（冗余，便于排序与聚合，免去拆串）。 */
    private Integer hitCount;

    /** 智能体名。 */
    private String agentName;

    /** 会话 ID。 */
    private String sessionId;

    /** 用户 ID。 */
    private String userId;

    /** 原文片段（已按配置截断）。 */
    private String snippet;

    /** 命中时刻（毫秒）。 */
    private Long createdAtMs;
}
