package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 主体级速率配额过滤器：HTTP 侧的判定入口（登录用户 / 匿名 IP / API Key 接入方三类主体）。
 *
 * <p><b>Order 必须排在两个鉴权过滤器之后</b>（{@code +30} > ApiKey 的 {@code +10} 与 UserAuth 的 {@code +20}）：
 * 主体身份由它们解析，抢在前面执行只会拿到一个还没鉴权的请求，把登录用户全按匿名 IP 限。</p>
 *
 * <p><b>为什么还要自己验一次令牌</b>：{@code UserAuthWebFilter} 只覆盖 {@code /api/customer/user/**}，
 * 而 {@code /api/customer/chat} 是允许匿名的裸对话入口——带着登录态打这条路径的请求，
 * 在那里不会被解析出用户。若不自己验，同一个人换条路径就能从"按人限"退化成"按 IP 限"，
 * 而 IP 那一档还是共享的，等于给了一条绕过的门缝。已解析过的（属性里有主体）直接复用，不重复验签。</p>
 *
 * <p>放行时把主体写入 {@link QuotaSubjectContext} 与 Reactor Context，供链路末端的 token 记账取用；
 * 超限返回 429 并带 {@code Retry-After}。</p>
 * @author owlzhangfq@gmail.com
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class SubjectQuotaWebFilter implements WebFilter {

    /** 免于判定的路径：健康检查与监控端点被限会让探活先挂，那是自伤。 */
    private static final String PATH_ACTUATOR = "/actuator";
    private static final String PATH_HEALTH = "/api/customer/health";

    /**
     * 额度自查接口同样豁免。
     *
     * <p>它落在默认覆盖的 {@code /api/customer/user/} 之下，不豁免的话会有两个荒谬后果：
     * 查一次"我还剩多少"就扣掉一次额度；以及额度耗尽后连"还剩多少"都查不到——
     * 偏偏那正是用户最需要看到它的时刻。</p>
     */
    private static final String PATH_MY_QUOTA = "/api/customer/user/quota";
    private static final String BEARER_PREFIX = "Bearer ";

    private final CustomerWorkProperties properties;
    private final SubjectQuotaGuard guard;

    /** 可为 null：宿主没装配用户登录态时，登录用户一律退化为按 IP 判定。 */
    private final UserJwtService jwtService;

    public SubjectQuotaWebFilter(CustomerWorkProperties properties,
                                 SubjectQuotaGuard guard,
                                 UserJwtService jwtService) {
        this.properties = properties;
        this.guard = guard;
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!guard.isEnabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (isExempt(path) || !matches(path)) {
            return chain.filter(exchange);
        }
        QuotaSubject subject = resolveSubject(exchange);
        SubjectQuotaDecision decision = guard.check(subject, path);
        if (decision.shouldBlock()) {
            return tooManyRequests(exchange, decision);
        }
        guard.recordRequest(subject);
        return chainWithSubject(exchange, chain, subject);
    }

    /**
     * 解析本次请求的限流主体。
     *
     * <p>优先级：已鉴权的用户 &gt; 自行验签得到的用户 &gt; API Key &gt; 来源 IP。
     * 顺序即"身份可信度从高到低"，每退一级，额度的共享面就大一圈。</p>
     */
    private QuotaSubject resolveSubject(ServerWebExchange exchange) {
        UserPrincipal principal = principalOf(exchange);
        if (principal != null) {
            return QuotaSubject.user(principal.userId());
        }
        String apiKey = exchange.getRequest().getHeaders()
            .getFirst(properties.getSecurity().getAuth().getHeaderName());
        if (apiKey != null && !apiKey.isBlank()) {
            return QuotaSubject.apiKey(apiKey);
        }
        return QuotaSubject.ip(remoteAddress(exchange));
    }

    /** 取已鉴权主体；没有则尝试自行验签（覆盖允许匿名的对话入口）。 */
    private UserPrincipal principalOf(ServerWebExchange exchange) {
        Object attribute = exchange.getAttributes().get(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (attribute instanceof UserPrincipal principal) {
            return principal;
        }
        if (jwtService == null) {
            return null;
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        // 验签失败不报错：这里只是想认出"他是谁"，令牌无效自有鉴权过滤器去拒，本类只管退回按 IP 限
        return jwtService.verify(header.substring(BEARER_PREFIX.length())).orElse(null);
    }

    private static String remoteAddress(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress() == null
            ? QuotaSubject.UNKNOWN_ID
            : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * 把主体写进下游上下文。
     *
     * <p>ThreadLocal 与 Reactor Context 都写，理由同租户上下文：前者供未发生线程切换时的同步代码读取，
     * 后者在切到 boundedElastic（阻塞 IO、模型调用都在那里）时由自动传播还原。
     * 只写一个，token 记账就会在某一类链路上拿不到主体。</p>
     */
    private Mono<Void> chainWithSubject(ServerWebExchange exchange, WebFilterChain chain, QuotaSubject subject) {
        QuotaSubjectContext.set(subject);
        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put(QuotaSubjectContextThreadLocalAccessor.KEY, subject))
            .doFinally(signal -> QuotaSubjectContext.clear());
    }

    private boolean matches(String path) {
        SubjectQuotaProperties cfg = properties.getSubjectQuota();
        List<String> paths = cfg.getPaths();
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        for (String prefix : paths) {
            if (prefix != null && !prefix.isBlank() && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExempt(String path) {
        return path.startsWith(PATH_ACTUATOR) || path.equals(PATH_HEALTH) || path.startsWith(PATH_MY_QUOTA);
    }

    /** 429 + Retry-After；响应体形如 {@code {"status":429,"error":"Too Many Requests","message":...}}。 */
    private Mono<Void> tooManyRequests(ServerWebExchange exchange, SubjectQuotaDecision decision) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,
            String.valueOf(decision.retryAfterSeconds()));
        String body = "{\"status\":429,\"error\":\"Too Many Requests\",\"kind\":\"" + decision.kind()
            + "\",\"retryAfterSeconds\":" + decision.retryAfterSeconds()
            + ",\"message\":\"" + decision.message() + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
