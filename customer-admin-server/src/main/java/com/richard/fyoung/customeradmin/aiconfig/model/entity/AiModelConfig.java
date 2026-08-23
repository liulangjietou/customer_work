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
     * 归属租户；{@code default} 表示系统共享基线配置。
     *
     * <p>本表在 {@code TenantInterceptors.TENANT_IGNORED_TABLES} 忽略清单里，SQL 拦截器<b>不会</b>
     * 自动改写租户条件，因此这一列必须由 {@code ModelConfigService} 显式读写——
     * 那里实现了 {@code docs/多租户架构设计.md} §2.4 承诺的两级可见性。</p>
     */
    private String tenantId;

    /** 模型目录资产 ID；旧 Agent 仍引用本部署的 {@link #id}。 */
    private Long assetId;
    private String modelName;
    /** 租户内稳定部署编码。 */
    private String deploymentCode;
    /** 兼容旧字段：实际语义是接入协议，而不是模型厂商。 */
    private String provider;
    /** 新协议字段；迁移期与 {@link #provider} 双写。 */
    private String protocolAdapter;
    @JsonIgnore
    private String apiKey;
    /** SecretRef ID；迁移期优先读它，缺失时回退 {@link #apiKey}。 */
    private Long secretRefId;
    private String baseUrl;
    private String region;
    private String environment;
    private Integer endpointRevision;
    private String lifecycleStatus;
    /** 0=存量兼容免认证；1=ACTIVE 前必须通过且持续持有有效认证。 */
    private Integer certificationRequired;
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
