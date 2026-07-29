package com.richard.fyoung.customerwork.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典类型持久化对象（贫血数据袋）：与 {@code cw_dict_type} 表一一映射。
 * 驼峰字段由 MyBatis-Plus 下划线映射自动对应下划线列。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_dict_type")
public class DictTypeEntity {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 字典类型编码（唯一，如 order_status）。 */
    private String dictType;

    /** 类型名称（展示用，如 订单状态）。 */
    private String typeName;

    /** 备注说明。 */
    private String remark;

    /** 是否启用（1 启用 / 0 停用）。 */
    private Boolean enabled;

    private Long createdAtMs;
    private Long updatedAtMs;
}
