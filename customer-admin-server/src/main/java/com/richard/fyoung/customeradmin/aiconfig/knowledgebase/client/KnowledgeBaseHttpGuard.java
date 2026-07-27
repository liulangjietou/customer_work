package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 知识库地址校验：本链路唯一的地址防御点，由 {@link KnowledgeSearchClient} 在发起请求前调用
 * （fast fail，命中即抛业务异常，不进入真实请求）。
 *
 * <p><b>与 {@code SystemToolHttpGuard} 的信任边界差异（这也是不复用它、另起白名单的原因）</b>：
 * 系统工具的目标地址由<b>大模型</b>决定，默认必须拒内网；RAG 知识库的地址由<b>持
 * {@code knowledge-base:add/edit} 权限的管理员</b>在后台显式配置，且企业 RAG 服务基本都在内网
 * （用户本机即 {@code localhost:20002}）。因此这里默认放行内网/环回，否则功能直接不可用。
 * 复用系统工具的白名单会把 RAG 的内网地址一并授信给模型可控的 HTTP 工具，绝不可取。</p>
 *
 * <p>两种模式（见 {@link AdminRagProperties#getAllowedHosts()}）：
 * <ul>
 *   <li>默认（白名单为空）：只做两件事——协议必须是 http/https；DNS 解析后拒绝<b>链路本地</b>地址
 *       （169.254.0.0/16 与 IPv6 fe80::/10，云元数据服务 169.254.169.254 是典型 SSRF 目标，
 *       且绝无可能是一台 RAG 服务）。内网/环回一律放行；</li>
 *   <li>白名单非空（收紧模式）：只按 host 字符串匹配白名单，命中放行、否则拒绝（白名单本身即信任边界，
 *       不再做 DNS 与地址判定）。生产建议显式配置，把信任边界收敛到已知的几台 RAG 服务。</li>
 * </ul></p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeBaseHttpGuard {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseHttpGuard.class);

    private static final String ERROR_CODE = "RAG-HTTP-FORBIDDEN";
    private static final String WILDCARD_PREFIX = "*.";
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final AdminRagProperties properties;

    public KnowledgeBaseHttpGuard(AdminRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验目标 RAG 基址是否允许访问；不允许则 fast fail 抛出 {@link BizException}。
     * @param baseUrl 知识库配置里的服务基址
     */
    public void checkAllowed(String baseUrl) {
        URI uri = parseUri(baseUrl);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            log.error("rag base url blocked by scheme, code={}, url={}", ERROR_CODE, baseUrl);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "仅支持 http/https 协议: " + baseUrl);
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            log.error("rag base url without host, code={}, url={}", ERROR_CODE, baseUrl);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "URL 缺少 host: " + baseUrl);
        }

        List<String> whitelist = properties.getAllowedHosts();
        if (!CollectionUtils.isEmpty(whitelist)) {
            // 收紧模式：仅放行白名单内 host，不再做 DNS 与地址判定
            if (!hostWhitelisted(host, whitelist)) {
                log.error("rag base url blocked by whitelist, code={}, host={}", ERROR_CODE, host);
                throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "目标 host 不在白名单内: " + host);
            }
            return;
        }

        // 默认模式：放行内网/环回（RAG 通常内网部署），仅拦截链路本地（云元数据服务等）
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            log.error("rag base url dns resolve failed, code={}, host={}", ERROR_CODE, host, e);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "目标 host 无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress()) {
                log.error("rag base url blocked link-local address, code={}, host={}, ip={}",
                    ERROR_CODE, host, address.getHostAddress());
                throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN,
                    "目标地址指向链路本地/元数据服务，已拦截: " + host);
            }
        }
    }

    /** 解析 URL；URL 为空或格式非法直接 fast fail。 */
    private URI parseUri(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            log.error("rag base url blank, code={}", ERROR_CODE);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "baseUrl 不能为空");
        }
        try {
            return URI.create(baseUrl.trim());
        } catch (Exception e) {
            log.error("rag base url malformed, code={}, url={}", ERROR_CODE, baseUrl, e);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_HTTP_FORBIDDEN, "baseUrl 格式非法: " + baseUrl);
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
