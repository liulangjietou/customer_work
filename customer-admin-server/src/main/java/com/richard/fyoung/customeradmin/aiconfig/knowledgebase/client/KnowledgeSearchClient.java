package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 外部 RAG 知识库检索客户端（JDK 内置 {@link HttpClient}，与 {@code AdminModelFactory}/
 * {@code DashScopeEmbeddingClient} 同款手法，不引入 RestTemplate/WebClient）。
 *
 * <p>接口契约：{@code POST {baseUrl}/api/v1/knowledge/search}，请求头
 * {@code Authorization: Bearer {appkey}} + Content-Type + 可选自定义头，
 * 请求体 {@code {"query","app_id","top_n"}}；判成功 = HTTP 200 且响应体 {@code code == "OK"}。</p>
 *
 * <p><b>两层 API 的职责分工</b>：
 * <ul>
 *   <li>{@link #searchOne}：单库检索，返回<b>原始</b>召回（不做阈值过滤、不截断），失败 fast fail 抛
 *       {@link BizException}——供保存门禁与连通性测试使用（它们需要知道"到底哪里失败了"与真实命中条数）；</li>
 *   <li>{@link #searchAll}：多库<b>并发</b>检索 + 阈值过滤 + 合并按 score 倒排取 top-n，
 *       <b>任何失败/超时都吞掉并返回已成功的部分</b>——供对话链路使用，检索绝不打断对话。
 *       这是本功能唯一的防御式编程收敛处。</li>
 * </ul></p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class KnowledgeSearchClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchClient.class);

    private static final String SEARCH_PATH = "/api/v1/knowledge/search";
    private static final String RESPONSE_CODE_OK = "OK";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CODE_SEARCH_FAIL = "RAG-SEARCH-FAIL";
    /** 非 JSON 错误响应体透出的最大字符数（避免把网关 HTML 错误页整页塞进提示）。 */
    private static final int ERROR_BODY_MAX_CHARS = 200;
    private static final String CODE_HEADER_INVALID = "RAG-HEADER-INVALID";

    /**
     * 自定义头里被禁止覆盖的保留头：Authorization/Content-Type 由本客户端按知识库配置统一设置，
     * JDK HttpRequest 的 {@code header()} 是追加而非覆盖，重复设置会产生两个同名头，行为不可控。
     */
    private static final Set<String> RESERVED_HEADERS =
        Set.of(HEADER_AUTHORIZATION.toLowerCase(Locale.ROOT), HEADER_CONTENT_TYPE.toLowerCase(Locale.ROOT));

    /**
     * 多库并发检索专用线程池：与 Tomcat 请求线程、以及 ForkJoin common pool 都隔离——
     * common pool 是全 JVM 共享的，把可能阻塞数秒的 HTTP 调用丢进去会拖垮其它并行任务。
     * 检索是低频短任务，固定 8 线程足够；守护线程，不阻塞 JVM 退出。
     */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "rag-search-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final KnowledgeBaseHttpGuard httpGuard;
    private final AdminRagProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public KnowledgeSearchClient(KnowledgeBaseHttpGuard httpGuard, AdminRagProperties properties) {
        this.httpGuard = httpGuard;
        this.properties = properties;
        // 强制 HTTP/1.1：JDK HttpClient 默认 HTTP_2，对 http:// 会先发 h2c upgrade 协商；
        // 实测部分自建 RAG 服务（如 Node 实现）既不支持该协商也不回错误，导致请求挂起到超时。
        // RAG 检索是小报文短连接，用不上 HTTP/2 多路复用，固定 1.1 最稳。
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
            .build();
    }

    /** 当前使用的 HTTP 协议版本（同包测试用于守住"必须固定 HTTP/1.1"这条约束）。 */
    HttpClient.Version httpClientVersion() {
        return httpClient.version();
    }

    /**
     * 单库检索，返回原始召回（不过滤、不截断）。地址校验在此收口（{@link KnowledgeBaseHttpGuard}）。
     * 任何失败（地址被拦截 / 网络异常 / 非 200 / code != OK / 响应结构非法）均抛 {@link BizException}。
     */
    public List<KnowledgeNode> searchOne(KnowledgeBaseEndpoint endpoint, String query) {
        httpGuard.checkAllowed(endpoint.baseUrl());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("app_id", endpoint.appId());
            payload.put("top_n", endpoint.effectiveTopN());
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(endpoint.baseUrl()) + SEARCH_PATH))
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .header(HEADER_CONTENT_TYPE, endpoint.effectiveContentType())
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + endpoint.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body));
            parseExtraHeaders(objectMapper, endpoint.extraHeaders()).forEach(builder::header);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 错误详情只在响应体里（如 code=APP_ACCESS_DENIED / message=该 API Key 未被授权调用应用 xxx），
                // 只报状态码等于把唯一有用的线索丢掉，使用者无从判断是密钥错、应用未授权还是服务内部故障
                throw new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED,
                    "知识库服务返回 HTTP " + response.statusCode() + describeErrorBody(response.body()));
            }
            return parseNodes(objectMapper, endpoint.kbName(), response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("rag search failed, code={}, kbId={}, kbName={}", CODE_SEARCH_FAIL,
                endpoint.id(), endpoint.kbName(), e);
            throw new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, diagnose(e, endpoint));
        }
    }

    /**
     * 提取非 200 响应体里的错误详情：优先取约定的 {@code code}/{@code message} 字段，
     * 结构不符则退回原文截断（{@value #ERROR_BODY_MAX_CHARS} 字符）。响应体为空时返回空串。
     */
    static String describeErrorBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            String code = root.path("code").asText("");
            String message = root.path("message").asText("");
            if (StringUtils.hasText(code) || StringUtils.hasText(message)) {
                return "（" + (StringUtils.hasText(code) ? code : "-")
                    + (StringUtils.hasText(message) ? ": " + message : "") + "）";
            }
        } catch (Exception ignored) {
            // 非 JSON 响应体（如网关 HTML 错误页），退回原文截断
        }
        String trimmed = body.strip();
        return "（" + (trimmed.length() > ERROR_BODY_MAX_CHARS
            ? trimmed.substring(0, ERROR_BODY_MAX_CHARS) + "..." : trimmed) + "）";
    }

    /**
     * 把底层网络异常翻译成能直接定位问题的提示。
     *
     * <p>JDK 的连接类异常 message 常为 null（如 {@code ConnectException: null}），直接透出等于没有信息，
     * 使用者无从下手；这里按异常类型给出具体排查方向。</p>
     */
    String diagnose(Exception e, KnowledgeBaseEndpoint endpoint) {
        if (e instanceof ConnectException || e instanceof HttpConnectTimeoutException) {
            return "无法建立连接（" + endpoint.baseUrl() + "）：请确认服务已启动、地址与端口正确；"
                + "若地址用的是 localhost 而服务只监听 IPv6，可改填 http://[::1]:端口";
        }
        if (e instanceof UnknownHostException) {
            return "域名无法解析（" + endpoint.baseUrl() + "）：请检查服务地址拼写与 DNS";
        }
        if (e instanceof HttpTimeoutException) {
            return "请求超时（超过 " + properties.getRequestTimeoutSeconds() + " 秒未返回）：知识库服务响应过慢，"
                + "或该服务不支持 HTTP/2 协商导致挂起；可调大 admin.rag.request-timeout-seconds 后重试";
        }
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * 多库并发检索：各库独立请求（互不阻塞），按各自 {@code scoreThreshold} 过滤后合并，
     * 全局按 score 倒排取 top-n。
     *
     * <p><b>降级约定</b>：单库失败只记 error 日志并当作空召回，整体超时同样按已完成部分处理，
     * 绝不向上抛异常——对话链路的可用性优先于检索完整性。</p>
     *
     * <p>合并后的条数上限取各库 {@code topN} 的最大值：每个库自己声明"我最多贡献几条"，
     * 合并时用最宽松的那个当全局上限，既不会因为某个库配了 1 条就把其它库的召回砍掉，
     * 也不会无上限地把全部库的召回都塞进上下文。</p>
     */
    public List<KnowledgeNode> searchAll(List<KnowledgeBaseEndpoint> endpoints, String query) {
        if (CollectionUtils.isEmpty(endpoints) || !StringUtils.hasText(query)) {
            return List.of();
        }
        List<CompletableFuture<List<KnowledgeNode>>> futures = endpoints.stream()
            .map(endpoint -> CompletableFuture
                .supplyAsync(() -> filterByThreshold(searchOne(endpoint, query), endpoint), SEARCH_EXECUTOR)
                .exceptionally(ex -> {
                    log.error("rag search degraded to empty, code={}, kbId={}, kbName={}",
                        CODE_SEARCH_FAIL, endpoint.id(), endpoint.kbName(), ex);
                    return List.<KnowledgeNode>of();
                }))
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(properties.getRetrievalTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("rag search wait timeout or interrupted, code={}, kbCount={}",
                "RAG-SEARCH-TIMEOUT", endpoints.size(), e);
        }

        List<KnowledgeNode> merged = new ArrayList<>();
        for (CompletableFuture<List<KnowledgeNode>> future : futures) {
            // 整体超时后仍未完成的库直接跳过（getNow 给默认空值），已完成的照常合并
            merged.addAll(future.getNow(List.of()));
        }
        int limit = endpoints.stream().mapToInt(KnowledgeBaseEndpoint::effectiveTopN).max()
            .orElse(KnowledgeBaseEndpoint.DEFAULT_TOP_N);
        return merged.stream()
            .sorted(Comparator.comparing(KnowledgeNode::score, Comparator.reverseOrder()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /** 按知识库自身阈值过滤：score 严格小于阈值即丢弃；阈值为 0（默认）时全部保留。 */
    private List<KnowledgeNode> filterByThreshold(List<KnowledgeNode> nodes, KnowledgeBaseEndpoint endpoint) {
        BigDecimal threshold = endpoint.effectiveScoreThreshold();
        if (threshold.signum() <= 0) {
            return nodes;
        }
        return nodes.stream()
            .filter(node -> node.score().compareTo(threshold) >= 0)
            .collect(Collectors.toList());
    }

    /**
     * 解析检索响应：{@code {"code":"OK","data":{"nodes":[{content,score,doc_id,chunk_id}]}}}。
     * 包级可见 + 静态，便于离线单测直接喂样例响应。
     */
    static List<KnowledgeNode> parseNodes(ObjectMapper mapper, String kbName, String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String code = root.path("code").asText("");
        if (!RESPONSE_CODE_OK.equals(code)) {
            String message = root.path("message").asText("响应 code 非 OK");
            throw new BizException(ResultCode.KNOWLEDGE_BASE_SEARCH_FAILED, "知识库服务返回 code=" + code + ", message=" + message);
        }
        JsonNode nodes = root.path("data").path("nodes");
        if (!nodes.isArray()) {
            return List.of();
        }
        List<KnowledgeNode> result = new ArrayList<>(nodes.size());
        for (JsonNode node : nodes) {
            String content = node.path("content").asText("");
            if (!StringUtils.hasText(content)) {
                continue;
            }
            result.add(new KnowledgeNode(kbName, content,
                BigDecimal.valueOf(node.path("score").asDouble(0d)),
                node.path("doc_id").asText(""),
                node.path("chunk_id").asText("")));
        }
        return result;
    }

    /**
     * 解析自定义请求头（JSON 对象字符串 → header map），保留头被忽略。
     * 静态且公开：保存时校验（{@code KnowledgeBaseService}）与运行时解析共用同一份实现，避免两处规则漂移。
     *
     * @throws IllegalArgumentException JSON 非法或不是对象结构
     */
    public static Map<String, String> parseExtraHeaders(ObjectMapper mapper, String extraHeaders) {
        if (!StringUtils.hasText(extraHeaders)) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(extraHeaders);
        } catch (Exception e) {
            throw new IllegalArgumentException("extraHeaders 不是合法 JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("extraHeaders 必须是 JSON 对象，如 {\"X-Tenant\":\"a\"}");
        }
        Map<String, String> headers = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                log.info("[rag] reserved header ignored in extraHeaders, code={}, header={}", CODE_HEADER_INVALID, name);
                continue;
            }
            headers.put(name, field.getValue().asText(""));
        }
        return headers;
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
