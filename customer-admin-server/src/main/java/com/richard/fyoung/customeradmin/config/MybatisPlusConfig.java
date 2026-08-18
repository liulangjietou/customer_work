package com.richard.fyoung.customeradmin.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.richard.fyoung.customeradmin.datascope.DataScopeInnerInterceptor;
import com.richard.fyoung.customeradmin.datascope.DataScopeTables;
import com.richard.fyoung.customeradmin.datascope.DataScopeProperties;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置：租户行级过滤 + 数据范围行级过滤 + 分页（列表接口服务端分页，需求文档 3.2/4.1）。
 *
 * <p>租户拦截器复用 starter 的 {@link TenantInterceptors} 构建器（当普通构建器 new，不当配置类），
 * 与 {@code AdminOtelTracingConfig} 复用 starter 构建器的做法一致——admin 排除了 starter 的自动装配，
 * 但共享同一份"哪些表不参与过滤"的口径，两边分头维护迟早漂移。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /**
     * 插件链顺序有讲究：两道行级过滤都必须排在分页之前。
     * 分页插件会先跑一次 count 查询，过滤条件若还没拼上，count 与数据页的口径就对不上。
     *
     * <p>租户过滤在前、数据范围在后，与"先定哪个租户、再定谁的数据"的语义一致；
     * 两者互不依赖，顺序对结果无影响，只影响生成 SQL 里条件的先后。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(AdminTenantProperties tenantProperties,
                                                         DataScopeProperties dataScopeProperties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (tenantProperties.isEnabled()) {
            interceptor.addInnerInterceptor(
                TenantInterceptors.build(tenantProperties.getColumnName(), tenantProperties.getIgnoredTables()));
            log.info("admin tenant line filter enabled, column={}", tenantProperties.getColumnName());
        }
        if (dataScopeProperties.isEnabled()) {
            interceptor.addInnerInterceptor(new DataScopeInnerInterceptor());
            log.info("admin data scope filter enabled, tables={}", DataScopeTables.ownerColumns().size());
        }
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
