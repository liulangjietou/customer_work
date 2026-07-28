package com.richard.fyoung.customeradmin.contentguard.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 命中日志查询：分页列表与统计看板共用同一套筛选条件，保证"图上看到的"与"表里列的"始终是同一批数据。
 *
 * <p>{@code keyword}（父类）按命中词模糊匹配。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SensitiveWordHitLogPageQuery extends PageQuery {

    /** 命中方向枚举名，空表示不筛。 */
    private String direction;

    /** 处置动作枚举名，空表示不筛。 */
    private String action;

    /** 会话 ID 精确筛选。 */
    private String sessionId;

    /** 起始时间戳（毫秒，含）。 */
    private Long startMs;

    /** 结束时间戳（毫秒，含）。 */
    private Long endMs;
}
