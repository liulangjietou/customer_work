package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SystemToolHttpGuard} 单测：本类是 starter {@code HttpTargetGuard} 的薄壳，故这里只验证
 * <b>属于薄壳的三件事</b>——策略绑定（拒内网 + 读 {@code admin.system-tool.http.allowed-hosts} 白名单）、
 * 异常转译（{@code HttpTargetForbiddenException} → {@link BizException} 且错误码正确）。
 * 判定算法本身（通配匹配、IP 分类、DNS 场景）在 starter 的 {@code HttpTargetGuardTest} 覆盖，不在此重复。
 *
 * <p>用例全部走 IP 字面量或白名单匹配，不发真实请求、不触 DNS。</p>
 * @author owlzhangfq@gmail.com
 */
class SystemToolHttpGuardTest {

    private SystemToolHttpGuard guard(String... allowedHosts) {
        AdminSystemToolProperties properties = new AdminSystemToolProperties();
        if (allowedHosts.length > 0) {
            properties.getHttp().setAllowedHosts(List.of(allowedHosts));
        }
        return new SystemToolHttpGuard(properties);
    }

    @Test
    void defaultMode_shouldBindDenyInternalPolicy() {
        // 目标地址由大模型决定 → 默认策略必须是拒内网/环回、放公网
        assertThrows(BizException.class, () -> guard().checkAllowed("http://10.1.2.3/api"));
        assertThrows(BizException.class, () -> guard().checkAllowed("http://127.0.0.1:9000/x"));
        assertThrows(BizException.class, () -> guard().checkAllowed("http://169.254.169.254/latest/meta-data"));
        assertDoesNotThrow(() -> guard().checkAllowed("http://8.8.8.8/x"));
    }

    @Test
    void whitelistMode_shouldBindConfiguredAllowedHosts() {
        assertDoesNotThrow(() -> guard("api.example.com").checkAllowed("https://api.example.com/v1/x"));
        assertDoesNotThrow(() -> guard("*.example.com").checkAllowed("https://svc.internal.example.com/x"));
        assertThrows(BizException.class, () -> guard("api.example.com").checkAllowed("https://evil.attacker.com/x"));
    }

    @Test
    void blocked_shouldBeTranslatedToBizExceptionWithForbiddenCode() {
        BizException ex = assertThrows(BizException.class, () -> guard().checkAllowed("http://10.1.2.3/api"));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, ex.getResultCode());

        BizException whitelistEx = assertThrows(BizException.class,
            () -> guard("api.example.com").checkAllowed("https://evil.attacker.com/x"));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, whitelistEx.getResultCode());

        BizException blankEx = assertThrows(BizException.class, () -> guard().checkAllowed("  "));
        assertEquals(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, blankEx.getResultCode());
    }
}
