package com.richard.fyoung.customeradmin.workbench.entity;

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
 * 内网工作台个人访问令牌：供 ScriptCat 通用脚本回调 admin-server 取站点凭证时鉴权。
 *
 * <p>令牌明文只在创建时返回一次，库里只存 {@code tokenHash}（SHA-256 十六进制，{@code @JsonIgnore}
 * 兜底不外泄）；{@code tokenPrefix} 供列表展示定位。可设过期时间、可吊销。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("workbench_token")
public class WorkbenchToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 令牌所属用户 ID。 */
    private Long userId;
    /** 令牌用途备注。 */
    private String name;
    @JsonIgnore
    private String tokenHash;
    /** 令牌前缀（如 wbt_ab12cd34），列表展示用。 */
    private String tokenPrefix;
    /** 过期时间，NULL 表示永不过期。 */
    private LocalDateTime expireTime;
    /** 最近一次使用时间。 */
    private LocalDateTime lastUsedTime;
    /** 是否已吊销：0否 / 1是。 */
    private Integer revoked;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    /**
     * 归属租户。其余表靠拦截器自动补写、实体不需要这个字段，此处是例外：
     * 脚本回调没有登录态，必须先跨租户按 token 定位到这一行，再从行里读出租户去还原上下文。
     */
    private String tenantId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
