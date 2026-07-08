package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型配置。{@code apiKey} 为 AES/GCM 密文，永不通过接口原样返回给前端
 * （{@code @JsonIgnore} 兜底，真正的回显值走 {@code ModelVO} 的脱敏字段）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_model_config")
public class AiModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;
    /** 当前仅支持 openai（需求文档 3.1），预留扩展。 */
    private String provider;
    @JsonIgnore
    private String apiKey;
    private String baseUrl;
    private String model;
    private BigDecimal temperature;
    private Integer maxTokens;
    /** 0否 / 1是。 */
    private Integer isDefault;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /** 0未测试 / 1成功 / 2失败。 */
    private Integer testStatus;
    private LocalDateTime testTime;

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
