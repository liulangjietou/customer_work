package com.richard.fyoung.customeradmin.aiconfig.systemtool.tool;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminSystemToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * HTTP 请求工具（{@code httpclient}）的 SSRF 收口防御点：这是该链路唯一的防御点，
 * 由 {@link HttpClientTools} 在发起请求前调用（fast fail，命中即抛业务异常，不进入真实请求）。
 *
 * <p>两种模式（见 {@link AdminSystemToolProperties}）：
 * <ul>
 *   <li>默认（白名单为空）：对 host 做 DNS 解析后，按解析出的 IP 判断——命中环回/内网/链路本地
 *       即拒绝（{@code internal.example.com} 这类"域名指向内网"的绕过也会被 IP 判定挡住），公网放行；</li>
 *   <li>白名单非空（收紧模式）：只按 host 字符串匹配白名单，命中放行、否则拒绝（不再做内网判定，
 *       白名单本身即为信任边界，无需 DNS）。</li>
 * </ul></p>
 *
 * <p>"唯一防御点"成立的前提：{@link HttpClientTools} 已按连接实例禁用自动重定向跟随
 * （{@code NoRedirectClientHttpRequestFactory}），3xx 作为终态响应返回。若重新打开自动跟随，
 * "合规公网域名 302 指向内网地址"即可绕过本类校验——改动前务必同步评估这里。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SystemToolHttpGuard {

    private static final Logger log = LoggerFactory.getLogger(SystemToolHttpGuard.class);

    private static final String ERROR_CODE = "SYSTOOL-HTTP-FORBIDDEN";
    private static final String WILDCARD_PREFIX = "*.";

    private final AdminSystemToolProperties properties;

    public SystemToolHttpGuard(AdminSystemToolProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验目标 URL 是否允许访问；不允许则 fast fail 抛出 {@link BizException}。
     * @param url 工具入参里的目标地址
     */
    public void checkAllowed(String url) {
        String host = extractHost(url);
        List<String> whitelist = properties.getHttp().getAllowedHosts();
        if (!CollectionUtils.isEmpty(whitelist)) {
            // 收紧模式：仅放行白名单内 host
            if (!hostWhitelisted(host, whitelist)) {
                log.error("http tool blocked by whitelist, code={}, host={}, url={}", ERROR_CODE, host, url);
                throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, "目标 host 不在白名单内: " + host);
            }
            return;
        }
        // 默认模式：DNS 解析后按 IP 拒内网/环回，放公网
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.error("http tool dns resolve failed, code={}, host={}, url={}", ERROR_CODE, host, url, e);
            throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, "目标 host 无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternalAddress(address)) {
                log.error("http tool blocked internal address, code={}, host={}, ip={}, url={}",
                    ERROR_CODE, host, address.getHostAddress(), url);
                throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN,
                    "目标地址指向内网/环回，已拦截: " + host);
            }
        }
    }

    /** 解析 URL 取 host；URL 非法或缺 host 直接 fast fail。 */
    private String extractHost(String url) {
        if (!StringUtils.hasText(url)) {
            log.error("http tool blocked blank url, code={}", ERROR_CODE);
            throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, "URL 不能为空");
        }
        String host;
        try {
            host = URI.create(url.trim()).getHost();
        } catch (Exception e) {
            log.error("http tool blocked malformed url, code={}, url={}", ERROR_CODE, url, e);
            throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, "URL 格式非法: " + url);
        }
        if (!StringUtils.hasText(host)) {
            log.error("http tool blocked url without host, code={}, url={}", ERROR_CODE, url);
            throw new BizException(ResultCode.SYSTEM_TOOL_HTTP_FORBIDDEN, "URL 缺少 host: " + url);
        }
        return host;
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

    /** 是否内网/环回/链路本地地址：覆盖 127/8、10/8、172.16/12、192.168/16、169.254/16、::1、fe80::、fc00::/7。 */
    boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        // Java 的 isSiteLocalAddress 不覆盖 IPv6 唯一本地地址 fc00::/7，手动判定
        if (address instanceof Inet6Address) {
            byte first = address.getAddress()[0];
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }
}
