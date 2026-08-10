package com.richard.fyoung.customeradmin.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户新增/编辑请求。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TenantSaveRequest {

    /** 主键；新增留空。 */
    private Long id;

    /**
     * 租户编码，创建后不可改——它会被写进各业务表的 tenant_id、API Key 映射、日志与指标标签，
     * 改一次等于让所有存量数据失去归属。
     * 限定字母数字与连字符：编码会出现在日志、指标标签与 Nacos dataId 里，特殊字符会带来转义问题。
     */
    @NotBlank(message = "租户编码不能为空")
    @Size(max = 64, message = "租户编码长度不能超过 64")
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9-_]*$", message = "租户编码只能包含字母、数字、连字符和下划线，且以字母或数字开头")
    private String tenantCode;

    @NotBlank(message = "租户名称不能为空")
    @Size(max = 128, message = "租户名称长度不能超过 128")
    private String tenantName;

    @Size(max = 64, message = "联系人长度不能超过 64")
    private String contactName;

    @Size(max = 32, message = "联系电话长度不能超过 32")
    private String contactPhone;

    @Email(message = "联系邮箱格式不正确")
    @Size(max = 128, message = "联系邮箱长度不能超过 128")
    private String contactEmail;

    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    /** 到期时间，空 = 不限期。 */
    private LocalDateTime expireTime;
}
