package com.richard.fyoung.customerchannel.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.support.WebClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * admin 开放 API 客户端（渠道接入层唯一出口）。
 *
 * <p>封装机器人列表拉取、会话 resolve/reset、以及对话 SSE 聚合。基于 Spring {@link WebClient}
 * （reactor-netty 已在依赖树内，无需新增依赖）。所有请求带 {@code X-Open-Api-Token} 头。</p>
 *
 * <p>约定 admin 返回统一 Result 包装 {@code {code, msg/message, data}}，本客户端只取 {@code data}。</p>
 * @author owlzhangfq@gmail.com
 */
public class AdminOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(AdminOpenApiClient.class);

    /** 非流式请求（列表/会话）的读超时，避免 admin 卡住时无限阻塞。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
        new ParameterizedTypeReference<ServerSentEvent<String>>() {
        };

    private final WebClient webClient;

    public AdminOpenApiClient(String baseUrl, String token) {
        this.webClient = WebClients.builder()
            .baseUrl(baseUrl)
            .defaultHeader(ChannelAccessConstants.OPEN_API_TOKEN_HEADER, token == null ? "" : token)
            // SSE 单条 event 上限放宽，防止长回复被 buffer 上限截断
            .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build();
    }

    /** 仅供测试注入自定义 WebClient。 */
    AdminOpenApiClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 拉取某渠道类型下启用中的机器人列表。
     *
     * @param channelType 渠道类型
     * @return 机器人列表（admin 不可达/解析失败由调用方决定重试，这里直接抛出）
     */
    public List<ChannelRobot> listRobots(String channelType) {
        JsonNode root = webClient.get()
            .uri(uri -> uri.path(ChannelAccessConstants.PATH_ROBOTS)
                .queryParam("channelType", channelType).build())
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(REQUEST_TIMEOUT);
        return parseRobots(root);
    }

    private List<ChannelRobot> parseRobots(JsonNode root) {
        List<ChannelRobot> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return result;
        }
        for (JsonNode n : data) {
            result.add(new ChannelRobot(
                n.path("id").isMissingNode() ? null : n.path("id").asLong(),
                n.path("channelType").asText(null),
                n.path("robotName").asText(null),
                n.path("appKey").asText(null),
                n.path("appSecret").asText(null),
                n.path("robotCode").asText(null),
                n.path("agentCode").asText(null),
                n.path("sessionMode").asText(null),
                n.path("version").isMissingNode() ? null : n.path("version").asLong()));
        }
        return result;
    }

    /** 解析会话（不存在则新建），返回 sessionId。 */
    public String resolveSession(String channelType, String appKey, String externalUserId) {
        return postSession(ChannelAccessConstants.PATH_SESSION_RESOLVE, channelType, appKey, externalUserId);
    }

    /** 重置会话（新会话），返回新的 sessionId。 */
    public String resetSession(String channelType, String appKey, String externalUserId) {
        return postSession(ChannelAccessConstants.PATH_SESSION_RESET, channelType, appKey, externalUserId);
    }

    private String postSession(String path, String channelType, String appKey, String externalUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelType", channelType);
        body.put("appKey", appKey);
        body.put("externalUserId", externalUserId);
        JsonNode root = webClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(REQUEST_TIMEOUT);
        if (root == null || root.get("data") == null) {
            throw new IllegalStateException("open api session response missing data: " + path);
        }
        String sessionId = root.get("data").path("sessionId").asText(null);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalStateException("open api session response missing sessionId: " + path);
        }
        return sessionId;
    }

    /**
     * 发起一轮对话并聚合 SSE 回复。
     *
     * <p>{@code event:message} 增量按序拼接；{@code event:done}（或 data {@code [DONE]}）结束；
     * {@code event:error} 抛出异常；聚合超过 {@code timeoutSeconds} 抛出超时异常。</p>
     *
     * @param agentCode      智能体编码
     * @param sessionId      会话 id
     * @param message        用户消息
     * @param timeoutSeconds 聚合超时（秒）
     * @return 聚合后的完整回复正文
     */
    public String chat(String agentCode, String sessionId, String message, int timeoutSeconds) {
        StringBuilder answer = new StringBuilder();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("message", message);
        webClient.post()
            .uri(ChannelAccessConstants.PATH_AGENT_CHAT, agentCode)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(SSE_TYPE)
            .takeUntil(this::isTerminal)
            .doOnNext(event -> aggregate(event, answer))
            .blockLast(Duration.ofSeconds(timeoutSeconds));
        return answer.toString();
    }

    private boolean isTerminal(ServerSentEvent<String> event) {
        return ChannelAccessConstants.SSE_EVENT_DONE.equals(event.event())
            || ChannelAccessConstants.SSE_DONE_MARKER.equals(event.data());
    }

    private void aggregate(ServerSentEvent<String> event, StringBuilder answer) {
        String type = event.event();
        if (ChannelAccessConstants.SSE_EVENT_ERROR.equals(type)) {
            throw new IllegalStateException("open api chat error event: " + event.data());
        }
        if (ChannelAccessConstants.SSE_EVENT_MESSAGE.equals(type) && event.data() != null) {
            answer.append(event.data());
        }
    }
}
