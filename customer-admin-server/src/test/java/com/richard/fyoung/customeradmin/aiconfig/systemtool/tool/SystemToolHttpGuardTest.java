package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * <p>用例全部走 IP 字面量或确定性解析器，不发真实请求、不触真实 DNS：白名单命中后也要解析地址按
 * IP 判定，域名用固定解析器给出确定性结果；IP 字面量不触 DNS，可直接用系统解析。</p>
 * @author owlzhangfq@gmail.com
 */
class SystemToolHttpGuardTest {

    private SystemToolHttpGuard guard(String... allowedHosts) {
        AdminSystemToolProperties properties = new AdminSystemToolProperties();
        if (allowedHosts.length > 0) {
            properties.getHttp().setAllowedHosts(List.of(allowedHosts));
        }
        // 确定性解析器：域名固定解析到公网地址（不触真实 DNS）；IP 字面量回落系统解析。
        return new SystemToolHttpGuard(properties, SystemToolHttpGuardTest::deterministicResolve);
    }

    private static InetAddress[] deterministicResolve(String host) throws UnknownHostException {
        return switch (host) {
            case "api.example.com", "svc.internal.example.com", "evil.attacker.com" ->
                new InetAddress[]{ InetAddress.getByName("93.184.216.34") };
            case "internal.corp" -> new InetAddress[]{ InetAddress.getByName("10.20.30.40") };
            default -> InetAddress.getAllByName(host);
        };
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
    void whitelistMode_shouldAllowLoopback_butStillRejectLinkLocal() {
        // 显式列入白名单即信任该 host（本地联调依赖它调 127.0.0.1）；
        // 命中后仍按 IP 判定，链路本地/云元数据地址即使伪造进白名单也拦得住。
        assertDoesNotThrow(() -> guard("127.0.0.1").checkAllowed("http://127.0.0.1:9000/x"));
        assertDoesNotThrow(() -> guard("api.example.com").checkAllowed("http://api.example.com/v1/x"));
        assertThrows(BizException.class, () -> guard("169.254.169.254").checkAllowed("http://169.254.169.254/latest/meta-data"));
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
