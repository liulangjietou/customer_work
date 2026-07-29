package com.richard.fyoung.customeradmin.dict.dto;

import lombok.Data;

/**
 * 字典类型视图对象。{@code itemCount} 为该类型下字典项数量（含停用），供列表页展示与删除前提示。
 * @author owlzhangfq@gmail.com
 */
@Data
public class DictTypeVO {

    private Long id;

    /** 字典类型编码（唯一，如 order_status）。 */
    private String dictType;

    /** 类型名称（展示用）。 */
    private String typeName;

    /** 备注说明。 */
    private String remark;

    /** 是否启用。 */
    private Boolean enabled;

    /** 该类型下字典项数量（含停用）。 */
    private Long itemCount;

    private Long createdAtMs;
    private Long updatedAtMs;
}
