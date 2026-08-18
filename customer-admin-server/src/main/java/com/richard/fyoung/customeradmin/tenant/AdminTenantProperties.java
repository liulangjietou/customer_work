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
 * <p>这里的 Java 默认值必须与 {@code application.yml} 以及 {@code TenantWebConfig} 上
 * {@code @ConditionalOnProperty} 的 {@code matchIfMissing} 保持同一个答案。三处一旦漂移，
 * 会出现"SQL 拦截器装了、写上下文的 Web 拦截器没装"这种最坏组合——每个请求都缺租户上下文，
 * 持久层 fail-closed，后台整体不可用。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.tenant")
public class AdminTenantProperties {

    /** 是否开启多租户行级隔离。 */
    private boolean enabled = true;

    /** 租户列名（与客服端库统一）。 */
    private String columnName = "tenant_id";

    /** 在内置平台级表之外，额外不参与租户过滤的表。 */
    private List<String> ignoredTables = new ArrayList<>();
}
