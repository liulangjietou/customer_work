package com.richard.fyoung.customerwork.safety.security;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * 目标地址解析出 IP 后的内网放行策略：同一套 SSRF 校验算法在不同信任边界下的唯一差异点。
 *
 * <p>两种策略的取舍由"目标地址由谁决定"决定：
 * <ul>
 *   <li>{@link #DENY_INTERNAL}：地址由<b>大模型</b>决定（如给智能体挂载的 HTTP 请求工具），
 *       必须默认拒内网——域名指向内网这类绕过也会被 IP 判定挡住；</li>
 *   <li>{@link #ALLOW_INTERNAL}：地址由<b>管理员显式配置</b>（如 RAG 知识库服务基址），
 *       企业内部服务本就部署在内网，默认拒内网等于功能不可用；但链路本地地址
 *       （169.254.0.0/16 与 IPv6 fe80::/10，云元数据服务 169.254.169.254 是典型 SSRF 目标）
 *       绝无可能是一台业务服务，一律拦截。</li>
 * </ul>
 * {@link #DENY_INTERNAL} 拦截的地址集合是 {@link #ALLOW_INTERNAL} 的超集。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public enum InternalAddressPolicy {

    /** 拒绝环回/任意本地/链路本地/私有网段/组播地址，只放行公网（模型可控地址的默认策略）。 */
    DENY_INTERNAL("目标地址指向内网/环回，已拦截: ") {
        @Override
        public boolean rejects(InetAddress address) {
            if (alwaysForbidden(address) || address.isSiteLocalAddress()) {
                return true;
            }
            // Java 的 isSiteLocalAddress 不覆盖 IPv6 唯一本地地址 fc00::/7，手动判定
            if (address instanceof Inet6Address) {
                byte first = address.getAddress()[0];
                return (first & 0xfe) == 0xfc;
            }
            return false;
        }
    },

    /**
     * 仅允许显式白名单中的企业私网地址：放行 RFC1918/ULA，仍拒绝环回、链路本地/元数据、
     * 未指定地址与组播地址。用于管理员配置的自建模型端点，不能用于模型自行决定的 URL。
     */
    ALLOW_PRIVATE_NETWORK("目标地址指向环回/链路本地/元数据/组播，已拦截: ") {
        @Override
        public boolean rejects(InetAddress address) {
            return alwaysForbidden(address);
        }
    },

    /** 放行内网/环回，仅拒绝链路本地地址（管理员显式配置地址的默认策略）。 */
    ALLOW_INTERNAL("目标地址指向链路本地/元数据服务，已拦截: ") {
        @Override
        public boolean rejects(InetAddress address) {
            return address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress();
        }
    };

    private final String rejectReasonPrefix;

    InternalAddressPolicy(String rejectReasonPrefix) {
        this.rejectReasonPrefix = rejectReasonPrefix;
    }

    /** 该地址是否应被本策略拒绝。 */
    public abstract boolean rejects(InetAddress address);

    private static boolean alwaysForbidden(InetAddress address) {
        return address.isLoopbackAddress() || address.isAnyLocalAddress()
            || address.isLinkLocalAddress() || address.isMulticastAddress();
    }

    /** 拦截原因描述（拼接被拦的 host，回给调用方定位）。 */
    public String rejectReason(String host) {
        return rejectReasonPrefix + host;
    }
}
