package com.richard.fyoung.customeradmin.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台多租户配置（{@code admin.tenant.*}）。
 *
 * <p><b>默认开启</b>：后台有来自不同租户的登录用户，关掉等于跨租户数据完全打通。
 * 单租户部署也不必关——所有数据都在同一个 {@code tenant_id} 下，过滤条件恒真，行为与关掉一致。</p>
 *
 * <p><b>Java 字段默认值刻意保持 {@code false}，"默认开启"只写在 {@code application.yml} 里</b>
 * （{@code ADMIN_TENANT_ENABLED} 可覆盖）。这个默认值只在配置完全缺失时才生效——那种场景下
 * {@code TenantWebConfig} 的 {@code @ConditionalOnProperty} 同样不装配，两者一致地退回单租户行为。
 * 若把它改成 {@code true} 而 Web 配置仍是缺省不装，就会出现"SQL 拦截器装了、写上下文的没装"
 * 这种最坏组合：每个请求都缺租户上下文，持久层 fail-closed，后台整体不可用。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.tenant")
public class AdminTenantProperties {

    /** 是否开启多租户行级隔离（生效值见 application.yml，默认 true）。 */
    private boolean enabled = false;

    /** 租户列名（与客服端库统一）。 */
    private String columnName = "tenant_id";

    /** 在内置租户忽略表之外，额外不参与租户过滤的表。 */
    private List<String> ignoredTables = new ArrayList<>();
}
