package com.richard.fyoung.customerwork.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典项持久化对象（贫血数据袋）：与 {@code cw_dict_item} 表一一映射。
 * {@code (dict_type, item_key)} 唯一。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_dict_item")
public class DictItemEntity {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属字典类型编码。 */
    private String dictType;

    /** 字典项键（业务值）。 */
    private String itemKey;

    /** 字典项标签（展示文案）。 */
    private String itemLabel;

    /** 排序号，越小越靠前。 */
    private Integer sort;

    /** 是否启用（1 启用 / 0 停用）。 */
    private Boolean enabled;

    /** 备注说明。 */
    private String remark;

    private Long createdAtMs;
    private Long updatedAtMs;
}
