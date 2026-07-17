package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品持久化对象（贫血数据袋，仅承载 {@code cw_product} 表的行映射）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_product")
public class ProductDO {

    /** 商品ID（应用赋值，非自增）。 */
    @TableId(value = "product_id", type = IdType.INPUT)
    private String productId;

    /** 商品名称。 */
    private String name;

    /** 品类。 */
    private String category;

    /** 价格。 */
    private BigDecimal price;

    /** 库存。 */
    private int stock;

    /** 商品描述。 */
    private String description;

    /** 优惠活动。 */
    private String promotion;

    /** 状态：ON_SALE/OFF_SALE。 */
    private String status;
}
