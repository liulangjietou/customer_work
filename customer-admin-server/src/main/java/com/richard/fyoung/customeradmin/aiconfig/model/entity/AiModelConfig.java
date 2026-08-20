package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

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

    /**
     * 归属租户；{@code __platform__} 表示平台级共享配置。
     *
     * <p>本表在 {@code TenantInterceptors.PLATFORM_LEVEL_TABLES} 忽略清单里，SQL 拦截器<b>不会</b>
     * 自动改写租户条件，因此这一列必须由 {@code ModelConfigService} 显式读写——
     * 那里实现了 {@code docs/多租户架构设计.md} §2.4 承诺的两级可见性。</p>
     */
    private String tenantId;

    private String modelName;
    /** 模型厂商：openai / dashscope / anthropic / gemini（见 {@code ModelProvider} 枚举）。 */
    private String provider;
    @JsonIgnore
    private String apiKey;
    private String baseUrl;
    private String model;
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
