package com.richard.fyoung.customeradmin.billing.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型单价。
 *
 * <p>单价按「元/百万 token」存：各厂商官网报价本身就是这个口径，照抄不用换算；
 * 若按「元/token」存，小数位多到 DECIMAL 精度与可读性都难兼顾。</p>
 *
 * <p><b>调价插新行而不是改旧行</b>：{@code effective_from} 让历史账单按当时的价格算得回去。
 * 改旧行会让已出账的数字在下次查询时悄悄变掉。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_model_price")
public class AiModelPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String provider;
    private String modelName;
    /** 输入单价（元/百万 token）。 */
    private BigDecimal inputPrice;
    /** 输出单价（元/百万 token）。 */
    private BigDecimal outputPrice;
    /** 缓存命中输入单价（元/百万 token），各厂商折扣不同故单列而非按比例算。 */
    private BigDecimal cachedPrice;
    private String currency;
    private LocalDateTime effectiveFrom;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
