package com.richard.fyoung.customeradmin.contentguard.jdbc;

import lombok.Data;

/**
 * 聚合结果通用行：{@code label} 是分组键（动作名/方向名/命中词串/时间桶），{@code total} 是该组条数。
 *
 * <p>四种聚合形状一致，共用一个承载类，不为每种聚合各造一个只差字段名的 DO。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class ContentGuardCountRow {

    /** 分组键。 */
    private String label;

    /** 该组条数。 */
    private long total;
}
