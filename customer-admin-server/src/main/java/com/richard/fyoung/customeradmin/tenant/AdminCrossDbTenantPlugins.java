package com.richard.fyoung.customeradmin.tenant;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为 admin 自建的跨库 SqlSessionFactory 提供与主库一致的租户行级过滤。
 * 平台跨租户查询仍必须在已校验运营方权限后显式使用 CrossTenantOperations。
 */
@Component
public class AdminCrossDbTenantPlugins {

    private final AdminTenantProperties properties;

    public AdminCrossDbTenantPlugins(AdminTenantProperties properties) {
        this.properties = properties;
    }

    public List<Interceptor> create() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
            TenantInterceptors.build(properties.getColumnName(), properties.getIgnoredTables()));
        return List.of(interceptor);
    }
}
