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

    /** 当前生效的租户（运营方切换视角后与上一个不同）。 */
    private String effectiveTenantId;

    /** 是否平台运营方：只有它能切换视角。 */
    private Boolean platformOperator;
}
