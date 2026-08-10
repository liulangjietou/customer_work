package com.richard.fyoung.customeradmin.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台多租户配置（{@code admin.tenant.*}）。默认关闭，单租户部署行为与升级前完全一致。
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.tenant")
public class AdminTenantProperties {

    /** 是否开启多租户行级隔离。 */
    private boolean enabled = false;

    /** 租户列名（与客服端库统一）。 */
    private String columnName = "tenant_id";

    /** 在内置平台级表之外，额外不参与租户过滤的表。 */
    private List<String> ignoredTables = new ArrayList<>();
}
