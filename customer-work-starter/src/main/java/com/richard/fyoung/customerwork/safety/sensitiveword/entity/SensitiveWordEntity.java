package com.richard.fyoung.customerwork.safety.sensitiveword.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 敏感词持久化对象（贫血数据袋）：与 {@code cw_sensitive_word} 表一一映射。
 *
 * <p>{@code category} / {@code action} 以枚举名字符串落库，转换在
 * {@link com.richard.fyoung.customerwork.safety.sensitiveword.MybatisSensitiveWordStore} 完成。
 * 驼峰字段由 MyBatis-Plus 下划线映射自动对应下划线列。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_sensitive_word")
public class SensitiveWordEntity {

    /** 自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 敏感词原词面。 */
    private String word;

    /** 类目枚举名（POLITICS/PORN/ABUSE/COMPETITOR/CUSTOM）。 */
    private String category;

    /** 处置动作枚举名（BLOCK/MASK/REVIEW）。 */
    private String action;

    /** 是否启用（1 启用 / 0 停用）。 */
    private Boolean enabled;

    private Long createdAtMs;
    private Long updatedAtMs;
}
