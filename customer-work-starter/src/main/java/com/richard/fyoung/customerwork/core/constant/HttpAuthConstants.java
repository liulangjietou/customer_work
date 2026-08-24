package com.richard.fyoung.customerwork.core.constant;

/**
 * HTTP 鉴权相关的公共字面量。
 *
 * <p>只放标准库/Spring 没有提供的那部分：{@code Authorization}、{@code Content-Type} 这类请求头名
 * 一律用 {@code org.springframework.http.HttpHeaders} 的常量，不在这里重复定义。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class HttpAuthConstants {

    /** Bearer 令牌前缀（含尾部空格，截取令牌时直接用 {@code length()} 偏移）。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * 服务间调用的智能体令牌请求头。
     *
     * <p>客服端 {@code AgentAuthWebFilter} 验、后台的工单客户端发，两侧此前各写一份字面量：
     * 改一处的后果是整条服务间调用 401，而两边代码看上去都对。</p>
     */
    public static final String AGENT_TOKEN_HEADER = "X-Agent-Token";

    /** 结构化 API Key 的原始 secret 请求头。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    /** 结构化 API Key 的稳定逻辑标识请求头。 */
    public static final String API_KEY_ID_HEADER = "X-API-Key-Id";

    private HttpAuthConstants() {
    }
}
