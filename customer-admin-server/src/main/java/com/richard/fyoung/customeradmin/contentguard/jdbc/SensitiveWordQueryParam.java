package com.richard.fyoung.customeradmin.contentguard.jdbc;

import lombok.Data;

/**
 * 敏感词分页查询条件（读侧 XML 用）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordQueryParam {

    /** 词面模糊关键字。 */
    private String keyword;

    /** 类目枚举名，空表示不筛。 */
    private String category;

    /** 处置动作枚举名，空表示不筛。 */
    private String action;

    /** 启用状态，null 表示不筛。 */
    private Boolean enabled;

    /** 偏移量。 */
    private int offset;

    /** 每页条数。 */
    private int limit;
}
