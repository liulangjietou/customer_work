package com.richard.fyoung.customerwork.safety.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 出网目标地址校验（SSRF 收口）：任何"由外部输入决定目标地址"的 HTTP 调用链路，都应在发起请求前
 * 调用本类一次，作为该链路<b>唯一</b>的地址防御点（fast fail，命中即抛
 * {@link HttpTargetForbiddenException}，不进入真实请求）。
 *
 * <p>校验顺序：URL 非空 → URL 可解析 → 协议为 http/https → host 非空 →
 * 白名单非空则只做 host 匹配（白名单即信任边界，不再触网）；白名单为空则 DNS 解析后按
 * {@link InternalAddressPolicy} 逐个 IP 判定。"域名指向内网"这类绕过靠解析后的 IP 判定挡住。</p>
 *
 * <p><b>唯一防御点成立的前提</b>：调用方必须禁用 HTTP 自动重定向跟随。否则"一个能通过本校验的
 * 公网域名返回 302 Location 指向内网地址"即可绕过——3xx 必须作为终态结果返回，由调用方决定
 * 是否对下一跳再发一次请求（那样会重新过一遍本校验）。</p>
 *
 * <p>策略以 {@link Supplier} 注入：配置类 Bean 的白名单可能在运行期被刷新，每次校验现取即可，
 * 无需重建 Guard 实例。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class HttpTargetGuard {

    private static final Logger log = LoggerFactory.getLogger(HttpTargetGuard.class);

    private static final String ERROR_CODE = "HTTP-TARGET-FORBIDDEN";
    private static final String WILDCARD_PREFIX = "*.";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Supplier<HttpTargetPolicy> policySupplier;

    /** 固定策略（策略在应用生命周期内不变时使用）。 */
    public HttpTargetGuard(HttpTargetPolicy policy) {
        this(() -> policy);
    }

    /** 动态策略（白名单来自可刷新的配置对象时使用，每次校验现取）。 */
    public HttpTargetGuard(Supplier<HttpTargetPolicy> policySupplier) {
        this.policySupplier = policySupplier;
    }

    /**
     * 校验目标 URL 是否允许访问；不允许则 fast fail。
     *
     * @param url 目标地址
     * @throws HttpTargetForbiddenException 地址为空/非法/协议不支持/不在白名单/命中内网策略
     */
    public void checkAllowed(String url) {
        HttpTargetPolicy policy = policySupplier.get();
        URI uri = parseUri(url);

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            log.error("http target blocked by scheme, code={}, url={}", ERROR_CODE, url);
            throw new HttpTargetForbiddenException("仅支持 http/https 协议: " + url);
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            log.error("http target blocked without host, code={}, url={}", ERROR_CODE, url);
            throw new HttpTargetForbiddenException("URL 缺少 host: " + url);
        }

        List<String> whitelist = policy.getAllowedHosts();
        if (!CollectionUtils.isEmpty(whitelist)) {
            // 收紧模式：仅放行白名单内 host，不再做 DNS 与地址判定
            if (!hostWhitelisted(host, whitelist)) {
                log.error("http target blocked by whitelist, code={}, host={}", ERROR_CODE, host);
                throw new HttpTargetForbiddenException("目标 host 不在白名单内: " + host);
            }
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.error("http target dns resolve failed, code={}, host={}", ERROR_CODE, host, e);
            throw new HttpTargetForbiddenException("目标 host 无法解析: " + host);
        }
        InternalAddressPolicy internalPolicy = policy.getInternalAddressPolicy();
        for (InetAddress address : addresses) {
            if (internalPolicy.rejects(address)) {
                log.error("http target blocked by address policy, code={}, policy={}, host={}, ip={}",
                    ERROR_CODE, internalPolicy, host, address.getHostAddress());
                throw new HttpTargetForbiddenException(internalPolicy.rejectReason(host));
            }
        }
    }

    /** 解析 URL；URL 为空或格式非法直接 fast fail。 */
    private URI parseUri(String url) {
        if (!StringUtils.hasText(url)) {
            log.error("http target blocked blank url, code={}", ERROR_CODE);
            throw new HttpTargetForbiddenException("URL 不能为空");
        }
        try {
            return URI.create(url.trim());
        } catch (Exception e) {
            log.error("http target blocked malformed url, code={}, url={}", ERROR_CODE, url, e);
            throw new HttpTargetForbiddenException("URL 格式非法: " + url);
        }
    }

    /** host 是否命中白名单：精确域名 equalsIgnoreCase，或 {@code *.example.com} 匹配其子域。 */
    boolean hostWhitelisted(String host, List<String> whitelist) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (String pattern : whitelist) {
            if (!StringUtils.hasText(pattern)) {
                continue;
            }
            String lowerPattern = pattern.trim().toLowerCase(Locale.ROOT);
            if (lowerPattern.startsWith(WILDCARD_PREFIX)) {
                String suffix = lowerPattern.substring(WILDCARD_PREFIX.length() - 1); // 保留前导点 ".example.com"
                if (lowerHost.endsWith(suffix)) {
                    return true;
                }
            } else if (lowerHost.equals(lowerPattern)) {
                return true;
            }
        }
        return false;
    }
}
