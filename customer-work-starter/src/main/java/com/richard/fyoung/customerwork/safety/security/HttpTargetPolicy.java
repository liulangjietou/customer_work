package com.richard.fyoung.customerwork.safety.security;

import lombok.Getter;

import java.util.List;

/**
 * {@link HttpTargetGuard} 的校验策略（纯 POJO，不绑定任何配置框架）：白名单 + 内网放行策略。
 *
 * <p>白名单与内网策略是"二选一"的关系而非叠加：白名单非空时白名单本身即为信任边界，
 * 只做 host 字符串匹配、不再做 DNS 与地址判定；白名单为空时才回落到按解析 IP 判内网。</p>
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

    private HttpTargetPolicy(List<String> allowedHosts, InternalAddressPolicy internalAddressPolicy) {
        this.allowedHosts = allowedHosts;
        this.internalAddressPolicy = internalAddressPolicy;
    }

    /**
     * 构造策略；两个参数均可为 null，分别回落为"空白名单"与"拒内网"——默认值一律取更严的一侧。
     *
     * @param allowedHosts          host 白名单，null 视作为空
     * @param internalAddressPolicy 内网放行策略，null 视作 {@link InternalAddressPolicy#DENY_INTERNAL}
     */
    public static HttpTargetPolicy of(List<String> allowedHosts, InternalAddressPolicy internalAddressPolicy) {
        return new HttpTargetPolicy(allowedHosts == null ? List.of() : allowedHosts,
            internalAddressPolicy == null ? InternalAddressPolicy.DENY_INTERNAL : internalAddressPolicy);
    }
}
