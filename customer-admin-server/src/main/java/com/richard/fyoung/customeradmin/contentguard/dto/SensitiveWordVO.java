package com.richard.fyoung.customeradmin.contentguard.dto;

import lombok.Data;

/**
 * 敏感词展示对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SensitiveWordVO {

    private Long id;

    /** 人读原词（运营维护的那个词面，非归一化产物）。 */
    private String word;

    /** 类目：POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM。 */
    private String category;

    /** 处置动作：BLOCK 拦截 / MASK 打码 / REVIEW 放行标记。 */
    private String action;

    /** 是否启用。 */
    private Boolean enabled;

    private Long createdAtMs;
    private Long updatedAtMs;
}
