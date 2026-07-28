package com.richard.fyoung.customeradmin.contentguard.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 敏感词分页查询：在通用分页之上加类目与动作两个筛选维度。
 *
 * <p>启停沿用父类的 {@code status}（0 停用 / 1 启用 / 不传不筛），不另造字段——后台各列表页的
 * 启停筛选契约是统一的，前端组件也是同一套。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SensitiveWordPageQuery extends PageQuery {

    /** 类目枚举名，空表示不筛。 */
    private String category;

    /** 处置动作枚举名，空表示不筛。 */
    private String action;
}
