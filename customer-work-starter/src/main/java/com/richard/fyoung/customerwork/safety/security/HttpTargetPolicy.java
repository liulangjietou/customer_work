package com.richard.fyoung.customerwork.safety.security;

import lombok.Getter;

import java.util.List;

/**
 * {@link HttpTargetGuard} 的校验策略（纯 POJO，不绑定任何配置框架）：白名单 + 地址策略。
 *
 * <p>既有调用方通过 {@link #of(List, InternalAddressPolicy)} 保持原语义：白名单非空时只匹配 host，
 * 不再做 DNS 与地址判定。凭据会随请求出网的链路应改用
 * {@link #ofResolvedAllowlist(List, InternalAddressPolicy, InternalAddressPolicy)}：即使 host 命中白名单，
 * 仍解析并校验本次连接地址，避免白名单域名通过 DNS rebinding 把凭据带到危险地址。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
public final class HttpTargetPolicy {

    /**
     * 允许访问的 host 白名单：支持精确域名（{@code api.example.com}）与通配后缀
     * （{@code *.example.com}，匹配其任意级子域，不匹配裸域名）。为空表示走内网策略判定。
     */
    private final List<String> allowedHosts;

    /** 白名单为空时的内网放行策略。 */
    private final InternalAddressPolicy internalAddressPolicy;

    /** 白名单命中后的地址策略；null 表示保持既有的“白名单即信任边界”语义。 */
    private final InternalAddressPolicy allowlistedAddressPolicy;

    private HttpTargetPolicy(List<String> allowedHosts,
                             InternalAddressPolicy internalAddressPolicy,
                             InternalAddressPolicy allowlistedAddressPolicy) {
        this.allowedHosts = allowedHosts;
        this.internalAddressPolicy = internalAddressPolicy;
        this.allowlistedAddressPolicy = allowlistedAddressPolicy;
    }

    /**
     * 构造策略；两个参数均可为 null，分别回落为"空白名单"与"拒内网"——默认值一律取更严的一侧。
     *
     * @param allowedHosts          host 白名单，null 视作为空
     * @param internalAddressPolicy 内网放行策略，null 视作 {@link InternalAddressPolicy#DENY_INTERNAL}
     */
    public static HttpTargetPolicy of(List<String> allowedHosts, InternalAddressPolicy internalAddressPolicy) {
        return new HttpTargetPolicy(allowedHosts == null ? List.of() : allowedHosts,
            internalAddressPolicy == null ? InternalAddressPolicy.DENY_INTERNAL : internalAddressPolicy,
            null);
    }

    /**
     * 构造“白名单仍解析地址”的策略。白名单为空时使用 {@code defaultAddressPolicy}；命中白名单时
     * 使用 {@code allowlistedAddressPolicy}。两个策略传 null 都回落到最严格的拒内网策略。
     */
    public static HttpTargetPolicy ofResolvedAllowlist(List<String> allowedHosts,
                                                        InternalAddressPolicy defaultAddressPolicy,
                                                        InternalAddressPolicy allowlistedAddressPolicy) {
        return new HttpTargetPolicy(allowedHosts == null ? List.of() : allowedHosts,
            defaultAddressPolicy == null ? InternalAddressPolicy.DENY_INTERNAL : defaultAddressPolicy,
            allowlistedAddressPolicy == null
                ? InternalAddressPolicy.DENY_INTERNAL : allowlistedAddressPolicy);
    }
}
