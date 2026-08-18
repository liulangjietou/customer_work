package com.richard.fyoung.customeradmin.contentguard.runtime;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感词词库加载的租户上下文测试。
 *
 * <p>刷新跑在 {@code SensitiveWordRefreshDriver} 的守护线程里，没有租户上下文。
 * 不显式跨租户会被拦截器 fail-closed，而读失败会让 {@code SensitiveWordFilter} 进入
 * "拦截一切"——后台对话全部被拦，且异常被 Store 的 catch 吞成一行日志，很难联想到租户开关。</p>
 * @author owlzhangfq@gmail.com
 */
class GatewaySensitiveWordStoreTenantTest {

    @Test
    void findEnabled_shouldQueryAcrossTenants() {
        boolean[] crossTenant = {false};
        GatewaySensitiveWordStore store = storeWith(() -> crossTenant[0] = true);

        store.findEnabled();

        assertTrue(crossTenant[0], "词库加载跑在守护线程里，必须显式跨租户，否则过滤器会 fail-closed 拦一切");
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"), "作用域退出后不得泄漏");
    }

    @Test
    void findAll_shouldQueryAcrossTenants() {
        boolean[] crossTenant = {false};
        GatewaySensitiveWordStore store = storeWith(() -> crossTenant[0] = true);

        store.findAll();

        assertTrue(crossTenant[0]);
    }

    /** 指纹用于判断词表是否变更，同样跑在守护线程里。 */
    @Test
    void fingerprint_shouldQueryAcrossTenants() {
        SensitiveWordMapper wordMapper = mock(SensitiveWordMapper.class);
        boolean[] crossTenant = {false};
        when(wordMapper.selectFingerprint()).thenAnswer(invocation -> {
            crossTenant[0] = InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId");
            return 1L;
        });

        new GatewaySensitiveWordStore(providerOf(wordMapper)).fingerprint();

        assertTrue(crossTenant[0]);
    }

    private GatewaySensitiveWordStore storeWith(Runnable onCrossTenant) {
        SensitiveWordMapper wordMapper = mock(SensitiveWordMapper.class);
        when(wordMapper.selectList(any())).thenAnswer(invocation -> {
            if (InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId")) {
                onCrossTenant.run();
            }
            return List.of();
        });
        return new GatewaySensitiveWordStore(providerOf(wordMapper));
    }

    private ContentGuardGatewayProvider providerOf(SensitiveWordMapper wordMapper) {
        ContentGuardGatewayProvider provider = mock(ContentGuardGatewayProvider.class);
        when(provider.get()).thenReturn(
            new ContentGuardGateway(wordMapper, null, null, null, null));
        return provider;
    }
}
