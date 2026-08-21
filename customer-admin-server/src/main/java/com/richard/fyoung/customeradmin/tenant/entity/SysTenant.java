package com.richard.fyoung.customeradmin.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户主数据。
 *
 * <p>本表自身不带 {@code tenant_id}——它是租户的定义方，参与不了自己的过滤，
 * 因此在 {@code TenantInterceptors.TENANT_IGNORED_TABLES} 忽略清单里，
 * 访问控制由 Controller 层的控制面角色与权限点双重校验负责。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_tenant")
public class SysTenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务主键，出现在 API Key 映射 / 日志 / 指标标签里，故用可读编码而非自增数字。 */
    private String tenantCode;
    private String tenantName;
    /** ACTIVE 正常 / SUSPENDED 冻结 / TERMINATED 退租，见 {@link TenantStatus}。 */
    private String status;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String remark;
    /** 到期时间，空 = 不限期。 */
    private LocalDateTime expireTime;

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
