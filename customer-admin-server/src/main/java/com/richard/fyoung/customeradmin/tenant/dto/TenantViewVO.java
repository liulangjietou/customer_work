package com.richard.fyoung.customeradmin.tenant.dto;

import lombok.Data;

/**
 * 当前登录用户的租户视角信息（前端据此决定是否渲染租户切换器）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TenantViewVO {

    /** 登录用户自身归属的租户。 */
    private String userTenantId;

    /** 当前生效的租户（控制面用户切换视角后与上一个不同）。 */
    private String effectiveTenantId;

    /** 是否具备跨租户控制面权限：只有具备该能力的用户能切换视角。 */
    private Boolean crossTenantAuthority;
}
