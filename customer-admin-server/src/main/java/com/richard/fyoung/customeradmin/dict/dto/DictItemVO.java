package com.richard.fyoung.customeradmin.dict.dto;

import lombok.Data;

/**
 * 字典项视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DictItemVO {

    private Long id;

    /** 所属字典类型编码。 */
    private String dictType;

    /** 字典项键（业务值）。 */
    private String itemKey;

    /** 字典项标签（展示文案）。 */
    private String itemLabel;

    /** 排序号，越小越靠前。 */
    private Integer sort;

    /** 是否启用。 */
    private Boolean enabled;

    /** 备注说明。 */
    private String remark;

    private Long createdAtMs;
    private Long updatedAtMs;
}
