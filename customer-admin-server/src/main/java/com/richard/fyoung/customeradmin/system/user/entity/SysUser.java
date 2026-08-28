package com.richard.fyoung.customeradmin.system.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台用户。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * 归属租户；缺省归入系统唯一保留的 {@code default}。
     *
     * <p>其它业务实体都不需要这个字段（写入由租户拦截器自动补、查询自动过滤），
     * 唯独它要显式持有：登录时正是靠它决定这个用户该进哪个租户的上下文。</p>
     */
    private String tenantId;

    /** BCrypt 哈希，永不通过接口返回给前端。 */
    @JsonIgnore
    private String password;

    private String nickname;

    /**
     * 注册邮箱：对外开放实例的自助注册必填（{@code RegistrationGuard#emailRequired()}），
     * LDAP 影子账号与管理员预建账号可空。
     *
     * <p>没有它就无法通知审核结果、无法找回密码，也无法在同一个人重复注册时识别出来——
     * 用户名可以随便再取一个，邮箱不行。</p>
     */
    private String email;

    /** 邮箱是否已验证：0否 / 1是。当前由管理员审核兜底，预留给后续的邮箱验证链路。 */
    private Integer emailVerified;
    /** 账号来源：LOCAL 本地账号 / LDAP 域账号（OA 单点登录，见 AuthService#ssoLogin）。 */
    private String loginType;

    /**
     * 配额等级编码（可为空 = 走配置里的 default-admin-level）。
     *
     * <p>等级定义本身在客服端库的 {@code cw_subject_quota_level}（与终端用户共用一张表、
     * 靠 {@code subject_type} 区分），这里只存绑定——绑定跟着用户表走，
     * 另建映射表会让每次限流判定多一次跨表查询，而 {@code sys_user} 本就要查。</p>
     */
    private String levelCode;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /** PENDING 待审核 / APPROVED 已通过 / REJECTED 已拒绝。 */
    private String approvalStatus;
    /** 最近一次审核人 sys_user.id。 */
    private Long approvalBy;
    /** 最近一次审核时间。 */
    private LocalDateTime approvalTime;
    /** 最近一次审核说明；拒绝原因可在登录后首页展示。 */
    private String approvalRemark;
    /** 认证版本；安全属性变化时原子递增，禁止通用 updateById 用旧值覆盖。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long authEpoch;
    private LocalDateTime lastLoginTime;
    private String lastLoginIp;

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
