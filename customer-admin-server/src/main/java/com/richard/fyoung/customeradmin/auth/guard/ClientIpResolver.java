package com.richard.fyoung.customeradmin.auth.guard;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 取请求的来源 IP，用于按 IP 的注册限流与登录锁定。
 *
 * <p>{@code X-Forwarded-For} 是逗号分隔的链路，最左边是原始客户端。是否采信由
 * {@code admin.registration.trust-forwarded-header} 决定——见该属性的说明，
 * 它的正确性依赖反向代理"覆写而非追加"这个部署前提。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    /** 解析来源 IP；拿不到时返回固定串，让限流退化为"全体共用一个桶"而不是放行。 */
    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader) {
        if (request == null) {
            return UNKNOWN;
        }
        if (trustForwardedHeader) {
            String forwarded = firstAddress(request.getHeader(HEADER_FORWARDED_FOR));
            if (isUsable(forwarded)) {
                return forwarded;
            }
            String realIp = request.getHeader(HEADER_REAL_IP);
            if (isUsable(realIp)) {
                return realIp.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return isUsable(remote) ? remote.trim() : UNKNOWN;
    }

    /**
     * 从当前请求上下文解析来源 IP，供拿不到 {@code HttpServletRequest} 参数的调用方使用。
     *
     * @return 来源 IP；不在 Web 请求线程上（调度线程、异步回调）返回 {@code null}，
     *         由调用方决定是记空还是跳过——限流路径不该走这个重载
     */
    public static String fromCurrentRequest(boolean trustForwardedHeader) {
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        String resolved = resolve(attrs.getRequest(), trustForwardedHeader);
        return UNKNOWN.equals(resolved) ? null : resolved;
    }

    private static String firstAddress(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        int comma = headerValue.indexOf(',');
        return (comma < 0 ? headerValue : headerValue.substring(0, comma)).trim();
    }

    /** 代理链路里 unknown 是常见占位值，当成拿不到处理。 */
    private static boolean isUsable(String value) {
        return StringUtils.hasText(value) && !UNKNOWN.equalsIgnoreCase(value.trim());
    }
}
