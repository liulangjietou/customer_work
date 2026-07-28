package com.richard.fyoung.customeradmin.contentguard.jdbc;

import lombok.Data;

/**
 * 命中日志查询条件（读侧 XML 用；分页与统计共用同一套筛选条件）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordHitLogQueryParam {

    /** 命中方向枚举名（INBOUND/OUTBOUND），空表示不筛。 */
    private String direction;

    /** 处置动作枚举名（BLOCK/MASK/REVIEW），空表示不筛。 */
    private String action;

    /** 命中词模糊关键字。 */
    private String keyword;

    /** 会话 ID 精确筛选。 */
    private String sessionId;

    /** 起始时间戳（毫秒，含）。 */
    private Long startMs;

    /** 结束时间戳（毫秒，含）。 */
    private Long endMs;

    /** 偏移量。 */
    private int offset;

    /** 每页条数。 */
    private int limit;
}
