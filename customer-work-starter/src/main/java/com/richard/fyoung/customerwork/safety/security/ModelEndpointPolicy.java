package com.richard.fyoung.customerwork.safety.security;

import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 模型端点唯一出网策略：保存配置与携带模型凭据的真实请求必须复用同一个实例。
 *
 * <p>默认只允许公网。企业自建模型若位于 RFC1918/ULA 私网，必须把 host 显式加入白名单；白名单
 * 只放宽私网这一类地址，环回、链路本地/云元数据、未指定地址与组播地址仍永久拒绝。白名单命中后
 * 仍会在每次连接前解析并校验 DNS，返回的地址列表应直接交给 HTTP 客户端，避免校验与连接之间再次
 * 独立解析形成 DNS rebinding 时间窗。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelEndpointPolicy {

    private final Supplier<List<String>> allowedHostsSupplier;
    private final HttpTargetGuard targetGuard;

    public ModelEndpointPolicy(Supplier<List<String>> allowedHostsSupplier) {
        this(allowedHostsSupplier, InetAddress::getAllByName);
    }

    /** 可替换解析器仅用于确定性测试；生产使用系统 DNS。 */
    public ModelEndpointPolicy(Supplier<List<String>> allowedHostsSupplier,
                               HttpTargetGuard.AddressResolver addressResolver) {
        this.allowedHostsSupplier = allowedHostsSupplier;
        this.targetGuard = new HttpTargetGuard(this::currentPolicy, addressResolver);
    }

    /**
     * 保存期校验并返回规范化 baseUrl。规范化只移除末尾斜杠与 host/scheme 大小写差异，
     * 不改变业务路径；query/fragment 不属于模型服务基址，直接拒绝。
     */
    public String validateAndNormalizeBaseUrl(String baseUrl) {
        HttpTargetGuard.ValidatedTarget target = targetGuard.validate(baseUrl);
        URI uri = target.uri();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new HttpTargetForbiddenException("模型 baseUrl 不允许包含 query 或 fragment");
        }
        return canonicalize(uri);
    }

    /** 比较两个端点是否等价；只用于判断是否必须重新提交凭据，不触发 DNS。 */
    public boolean sameEndpoint(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        try {
            return canonicalize(URI.create(left.trim())).equals(canonicalize(URI.create(right.trim())));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * HTTP 客户端连接期 DNS 回调：重新应用当前白名单和地址策略，并把同一次解析结果直接交给连接层。
     */
    public List<InetAddress> resolveForConnection(String hostname) {
        try {
            String host = hostname;
            if (hostname.indexOf(':') >= 0 && !hostname.startsWith("[")) {
                host = "[" + hostname + "]";
            }
            URI validationUri = new URI("http://" + host);
            return targetGuard.validate(validationUri.toASCIIString()).addresses();
        } catch (URISyntaxException e) {
            throw new HttpTargetForbiddenException("模型端点 host 格式非法");
        }
    }

    private HttpTargetPolicy currentPolicy() {
        return HttpTargetPolicy.ofResolvedAllowlist(currentAllowedHosts(),
            InternalAddressPolicy.DENY_INTERNAL,
            InternalAddressPolicy.ALLOW_PRIVATE_NETWORK);
    }

    private List<String> currentAllowedHosts() {
        List<String> configured = allowedHostsSupplier == null ? null : allowedHostsSupplier.get();
        if (CollectionUtils.isEmpty(configured)) {
            return List.of();
        }
        return configured.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .collect(Collectors.toUnmodifiableList());
    }

    private String canonicalize(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(scheme).append("://").append(host);
        if (uri.getPort() >= 0) {
            result.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        if (StringUtils.hasText(path) && !"/".equals(path)) {
            int end = path.length();
            while (end > 1 && path.charAt(end - 1) == '/') {
                end--;
            }
            result.append(path, 0, end);
        }
        return result.toString();
    }
}
