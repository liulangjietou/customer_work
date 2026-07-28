package com.richard.fyoung.customeradmin.contentguard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用计数项：分组键 + 条数。看板的四组统计形状一致，共用一个 VO。
 * @author owlzhangfq@gmail.com
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentGuardCountVO {

    /** 分组键（动作名/方向名/命中词/时间桶）。 */
    private String label;

    /** 该组条数。 */
    private long total;
}
