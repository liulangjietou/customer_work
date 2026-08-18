package com.richard.fyoung.customeradmin.datascope;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.richard.fyoung.customeradmin.config.MybatisPlusConfig;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.TenantWebConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器装配门控测试。
 *
 * <p>Service 层单测全程注入 mock，照不出"拦截器压根没进链"——那种失效不报错，
 * 只表现为过滤条件从未拼上，是最危险的一类回归。这里直接断言插件链的成员与顺序。</p>
 * @author owlzhangfq@gmail.com
 */
class DataScopeInterceptorAssemblyTest {

    private final MybatisPlusConfig config = new MybatisPlusConfig();

    /** 两道过滤默认都开；关掉任何一个都要靠显式配置，不能因为某次重构悄悄消失。 */
    @Test
    void bothFiltersShouldBeEnabledByDefault() {
        List<InnerInterceptor> chain = chainOf(new AdminTenantProperties(), new DataScopeProperties());

        assertTrue(chain.stream().anyMatch(i -> i instanceof TenantLineInnerInterceptor), "租户过滤应默认装配");
        assertTrue(chain.stream().anyMatch(i -> i instanceof DataScopeInnerInterceptor), "数据范围过滤应默认装配");
    }

    /**
     * 两道行级过滤都必须排在分页之前：分页插件会先跑一次 count 查询，
     * 过滤条件若还没拼上，count 与数据页的口径就对不上，翻页会出现空页。
     */
    @Test
    void rowFiltersShouldComeBeforePagination() {
        List<InnerInterceptor> chain = chainOf(new AdminTenantProperties(), new DataScopeProperties());

        int pagination = indexOf(chain, PaginationInnerInterceptor.class);
        assertTrue(indexOf(chain, TenantLineInnerInterceptor.class) < pagination, "租户过滤须在分页之前");
        assertTrue(indexOf(chain, DataScopeInnerInterceptor.class) < pagination, "数据范围过滤须在分页之前");
    }

    @Test
    void dataScopeFilterShouldBeAbsentWhenDisabled() {
        DataScopeProperties disabled = new DataScopeProperties();
        disabled.setEnabled(false);

        List<InnerInterceptor> chain = chainOf(new AdminTenantProperties(), disabled);

        assertFalse(chain.stream().anyMatch(i -> i instanceof DataScopeInnerInterceptor));
        assertTrue(chain.stream().anyMatch(i -> i instanceof TenantLineInnerInterceptor), "关数据范围不应连带关掉租户过滤");
    }

    @Test
    void tenantFilterShouldBeAbsentWhenDisabled() {
        AdminTenantProperties disabled = new AdminTenantProperties();
        disabled.setEnabled(false);

        List<InnerInterceptor> chain = chainOf(disabled, new DataScopeProperties());

        assertFalse(chain.stream().anyMatch(i -> i instanceof TenantLineInnerInterceptor));
        assertTrue(chain.stream().anyMatch(i -> i instanceof DataScopeInnerInterceptor), "两个开关相互独立");
    }

    /** 白名单条数与实现保持一致，防止有人加表却没同步更新装配日志里的口径。 */
    @Test
    void whitelistShouldNotBeEmpty() {
        assertEquals(9, DataScopeTables.ownerColumns().size());
    }

    /**
     * 开关的两处默认值必须给出同一个答案：Properties 的 Java 字段默认值决定持久层装不装 SQL 拦截器，
     * Web 配置类 {@code @ConditionalOnProperty} 的 matchIfMissing 决定装不装写上下文的 Web 拦截器。
     *
     * <p>两者漂移会出现最坏组合——SQL 拦截器装了、写上下文的没装，每个请求都缺上下文而 fail-closed，
     * 后台整体不可用；而这在配了 yml 的环境里完全看不出来，只在 yml 缺省时炸。</p>
     */
    @Test
    void switchDefaultsShouldAgreeBetweenPropertiesAndWebConfig() {
        assertEquals(new AdminTenantProperties().isEnabled(), matchIfMissingOf(TenantWebConfig.class),
            "admin.tenant.enabled 的 Java 默认值与 TenantWebConfig 的 matchIfMissing 不一致");
        assertEquals(new DataScopeProperties().isEnabled(), matchIfMissingOf(DataScopeWebConfig.class),
            "admin.data-scope.enabled 的 Java 默认值与 DataScopeWebConfig 的 matchIfMissing 不一致");
    }

    private boolean matchIfMissingOf(Class<?> configClass) {
        ConditionalOnProperty annotation = configClass.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation, configClass.getSimpleName() + " 应带 @ConditionalOnProperty");
        return annotation.matchIfMissing();
    }

    private List<InnerInterceptor> chainOf(AdminTenantProperties tenant, DataScopeProperties dataScope) {
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(tenant, dataScope);
        return interceptor.getInterceptors();
    }

    private int indexOf(List<InnerInterceptor> chain, Class<? extends InnerInterceptor> type) {
        for (int i = 0; i < chain.size(); i++) {
            if (type.isInstance(chain.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
