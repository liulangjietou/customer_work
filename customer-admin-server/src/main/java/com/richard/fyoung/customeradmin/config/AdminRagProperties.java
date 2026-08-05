package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部 RAG 知识库检索参数：{@code admin.rag.*}。
 *
 * <p><b>为什么不复用 {@code admin.system-tool.http.allowed-hosts}</b>：两者的信任边界方向相反。
 * 系统工具的 HTTP 请求工具，目标地址是<b>大模型自己决定</b>的，必须默认拒内网防 SSRF；
 * 而 RAG 知识库的地址是<b>管理员在后台显式配置</b>的，且企业 RAG 服务基本都部署在内网
 * （用户本机就是 {@code localhost:20002}），默认拒内网等于功能不可用。两套白名单各自独立，
 * 互不污染——把 RAG 的内网地址加进系统工具白名单，会顺带把模型可控的 HTTP 工具也放进内网。</p>
 *
 * <p>策略由 {@code KnowledgeBaseHttpGuard} 绑定（本链路唯一的地址防御点），判定算法在 starter 的
 * {@code HttpTargetGuard}（与系统工具地址校验同一份实现，差异只在内网放行策略枚举）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.rag")
public class AdminRagProperties {

    /**
     * 允许访问的 RAG 服务 host 白名单。为空=默认模式（放行内网/环回，仅拦截链路本地地址，
     * 即云元数据服务 169.254.169.254 一类绝不可能是 RAG 服务的目标）；
     * 非空=收紧模式（仅放行白名单内 host，支持精确域名与 {@code *.example.com} 通配后缀）。
     */
    private List<String> allowedHosts = new ArrayList<>();

    /** TCP 连接超时（秒）。 */
    private int connectTimeoutSeconds = 5;

    /** 单次检索请求的请求级超时（秒），检索与连通性探测共用。 */
    private int requestTimeoutSeconds = 8;

    /** 对话链路多库并发检索的整体等待上限（秒）：超时即按空召回降级，绝不拖死对话。 */
    private int retrievalTimeoutSeconds = 10;

    /** 连通性测试用的固定探测语句（真实发一次检索请求，返回命中条数）。 */
    private String testQuery = "连通性测试";
}
