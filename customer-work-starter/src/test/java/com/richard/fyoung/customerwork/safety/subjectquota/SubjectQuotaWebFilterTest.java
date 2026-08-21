package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.safety.security.UserJwtService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 判定入口单测：路径匹配、主体解析优先级、超限 429、豁免路径、上下文写入。
 * @author owlzhangfq@gmail.com
 */
class SubjectQuotaWebFilterTest {

    private static final String CHAT_PATH = "/api/customer/chat";

    private final CustomerWorkProperties properties = new CustomerWorkProperties();
    private final InMemorySubjectQuotaLevelStore levelStore = new InMemorySubjectQuotaLevelStore();
    private final InMemoryWindowCounter counter = new InMemoryWindowCounter();

    /** 记录链路末端看到的主体，用来验证上下文确实写进去了。 */
    private final AtomicReference<QuotaSubject> seen = new AtomicReference<>();

    private final WebFilterChain chain = exchange -> Mono.fromRunnable(
        () -> seen.set(QuotaSubjectContext.get()));

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
    }

    private SubjectQuotaGuard guard(boolean enabled) {
        properties.getSubjectQuota().setEnabled(enabled);
        SubjectLevelResolver resolver = new SubjectLevelResolver(
            new SubjectQuotaLevelProvider(levelStore, false), userId -> Optional.empty(),
            properties.getSubjectQuota());
        return new SubjectQuotaGuard(resolver, counter, new InMemorySubjectQuotaHitStore(), enabled);
    }

    private SubjectQuotaWebFilter filter(SubjectQuotaGuard guard) {
        return new SubjectQuotaWebFilter(properties, guard, null);
    }

    private MockServerWebExchange get(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
            .remoteAddress(new java.net.InetSocketAddress("10.0.0.7", 12345)));
    }

    @Test
    void filter_shouldPassThrough_whenDisabled() {
        SubjectQuotaWebFilter f = filter(guard(false));
        StepVerifier.create(f.filter(get(CHAT_PATH), chain)).verifyComplete();
        assertNull(seen.get(), "功能关闭时连上下文都不该写——省掉一次无谓的 ThreadLocal 写入");
    }

    @Test
    void filter_shouldPassThrough_whenPathNotCovered() {
        SubjectQuotaWebFilter f = filter(guard(true));
        StepVerifier.create(f.filter(get("/api/other/thing"), chain)).verifyComplete();
        assertNull(seen.get(), "清单外的路径不判定");
    }

    @Test
    void filter_shouldExemptQuotaSelfCheck() {
        // 不豁免的话：查一次"我还剩多少"就扣一次额度，且额度耗尽后连查都查不了
        SubjectQuotaWebFilter f = filter(guard(true));
        StepVerifier.create(f.filter(get("/api/customer/user/quota"), chain)).verifyComplete();
        assertNull(seen.get());
    }

    @Test
    void filter_shouldResolveIpSubject_whenNoCredential() {
        SubjectQuotaWebFilter f = filter(guard(true));
        StepVerifier.create(f.filter(get(CHAT_PATH), chain)).verifyComplete();

        assertNotNull(seen.get());
        assertEquals(QuotaSubjectType.IP, seen.get().type(), "无凭据时按来源 IP 算");
        assertEquals("10.0.0.7", seen.get().id());
    }

    @Test
    void filter_shouldResolveIpSubject_whenJwtContainsLegacyTenant() {
        UserJwtService jwtService = new UserJwtService(properties);
        String token = jwtService.issue("U-legacy", "legacy", "Legacy", "__platform__");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(CHAT_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.7", 12345)));
        SubjectQuotaWebFilter f = new SubjectQuotaWebFilter(properties, guard(true), jwtService);

        StepVerifier.create(f.filter(exchange, chain)).verifyComplete();

        assertEquals(QuotaSubjectType.IP, seen.get().type(), "旧平台租户令牌不能被识别成用户主体");
        assertEquals("10.0.0.7", seen.get().id());
    }

    @Test
    void filter_shouldResolveApiKeySubject_whenHeaderPresent() {
        String header = properties.getSecurity().getAuth().getHeaderName();
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(CHAT_PATH).header(header, "sk-abc")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.7", 12345)));

        StepVerifier.create(filter(guard(true)).filter(exchange, chain)).verifyComplete();
        assertEquals(QuotaSubjectType.API_KEY, seen.get().type(), "带 Key 时按接入方算，不退化到 IP");
    }

    @Test
    void filter_shouldPreferAuthenticatedPrincipal() {
        MockServerWebExchange exchange = get(CHAT_PATH);
        exchange.getAttributes().put(
            com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter.PRINCIPAL_ATTR,
            new com.richard.fyoung.customerwork.safety.security.UserPrincipal(
                "U-9", "alice", "Alice", TenantContext.DEFAULT));

        StepVerifier.create(filter(guard(true)).filter(exchange, chain)).verifyComplete();
        assertEquals(QuotaSubjectType.USER, seen.get().type(), "已鉴权的用户优先于 IP");
        assertEquals("U-9", seen.get().id());
    }

    @Test
    void filter_shouldReturn429_whenExceeded() {
        levelStore.save(new SubjectQuotaLevel(null, TenantContext.DEFAULT, "anonymous", "匿名",
            QuotaSubjectType.IP, 1800, 0, 1, SubjectExceedAction.BLOCK, true, null));
        SubjectQuotaGuard g = guard(true);
        SubjectQuotaWebFilter f = filter(g);

        StepVerifier.create(f.filter(get(CHAT_PATH), chain)).verifyComplete();

        MockServerWebExchange second = get(CHAT_PATH);
        StepVerifier.create(f.filter(second, chain)).verifyComplete();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
        assertEquals(List.of("1800"), second.getResponse().getHeaders().get(HttpHeaders.RETRY_AFTER),
            "429 必须带 Retry-After，否则客户端只能瞎猜什么时候能重试");
    }

    @Test
    void filter_shouldClearContext_afterRequest() {
        SubjectQuotaWebFilter f = filter(guard(true));
        StepVerifier.create(f.filter(get(CHAT_PATH), chain)).verifyComplete();
        assertTrue(QuotaSubjectContext.get() == null,
            "请求结束必须清理 ThreadLocal，否则线程复用会把上一个请求的身份带给下一个人");
    }
}
