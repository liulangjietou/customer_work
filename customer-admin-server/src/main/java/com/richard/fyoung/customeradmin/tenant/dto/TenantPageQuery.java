package com.richard.fyoung.customeradmin.tenant.dto;

import com.richard.fyoung.customeradmin.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户分页查询。
 *
 * <p>不复用父类的 {@code status}（Integer 的 0/1 启停语义）：租户是三态生命周期
 * ACTIVE/SUSPENDED/TERMINATED，硬塞进两态布尔会丢掉"退租"与"冻结"的区别。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantPageQuery extends PageQuery {

    /** 生命周期状态枚举名，空表示不筛。 */
    private String tenantStatus;
}
