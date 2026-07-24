package com.richard.fyoung.customerchannel.access.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.support.WebClients;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 微信 access_token 客户端（按 appId 缓存，提前 5 分钟刷新）。
 *
 * <p>{@code GET /cgi-bin/token?grant_type=client_credential&appid=&secret=} 换取 access_token。微信侧
 * access_token 有并发/频次限制且全局唯一，故按 appId 本地缓存复用，仅在缺失或临近过期时刷新；调用方
 * （客服消息发送）遇 40001/42001 可显式 {@link #forceRefresh} 强刷一次再重试。</p>
 * @author owlzhangfq@gmail.com
 */
public class WeChatAccessTokenClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String GRANT_TYPE = "client_credential";

    private final WebClient webClient;
    /** appId → 缓存的 token。 */
    private final ConcurrentMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    public WeChatAccessTokenClient() {
        this(WebClients.builder().baseUrl(ChannelAccessConstants.WECHAT_API_BASE_URL).build());
    }

    /** 仅供测试注入自定义 baseUrl 的 WebClient。 */
    WeChatAccessTokenClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 获取 access_token：缓存有效则直接返回，否则刷新。
     *
     * @param appId  公众号 AppID（= 机器人 appKey）
     * @param secret 公众号 AppSecret（= 机器人 appSecret）
     * @return 有效的 access_token
     */
    public String getToken(String appId, String secret) {
        CachedToken cached = cache.get(appId);
        if (cached != null && !cached.needsRefresh()) {
            return cached.token;
        }
        return refreshIfStale(appId, secret);
    }

    /** 忽略缓存强制刷新（用于业务 API 返回 token 失效错误码后的重试）。 */
    public synchronized String forceRefresh(String appId, String secret) {
        return fetchAndCache(appId, secret);
    }

    /** 临近过期时刷新：双重检查避免并发多线程重复打微信（forceRefresh 不走此路，需无条件刷新）。 */
    private synchronized String refreshIfStale(String appId, String secret) {
        CachedToken cached = cache.get(appId);
        if (cached != null && !cached.needsRefresh()) {
            return cached.token;
        }
        return fetchAndCache(appId, secret);
    }

    /** 无条件拉取并写缓存。 */
    private String fetchAndCache(String appId, String secret) {
        JsonNode root = webClient.get()
            .uri(uri -> uri.path(ChannelAccessConstants.WECHAT_PATH_ACCESS_TOKEN)
                .queryParam("grant_type", GRANT_TYPE)
                .queryParam("appid", appId)
                .queryParam("secret", secret)
                .build())
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(REQUEST_TIMEOUT);
        if (root == null) {
            throw new IllegalStateException("wechat access_token response is null");
        }
        int errcode = root.path("errcode").asInt(0);
        String token = root.path("access_token").asText("");
        if (errcode != 0 || token.isEmpty()) {
            throw new IllegalStateException("wechat access_token fetch failed, errcode="
                + errcode + ", errmsg=" + root.path("errmsg").asText(""));
        }
        long expiresInSeconds = root.path("expires_in").asLong(7200L);
        long expireAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L;
        cache.put(appId, new CachedToken(token, expireAtMillis));
        return token;
    }

    /** 带过期时间的缓存 token。 */
    private static final class CachedToken {
        private final String token;
        private final long expireAtMillis;

        private CachedToken(String token, long expireAtMillis) {
            this.token = token;
            this.expireAtMillis = expireAtMillis;
        }

        /** 提前 5 分钟视为需要刷新，避免临界期用到即将过期的 token。 */
        private boolean needsRefresh() {
            long aheadMillis = ChannelAccessConstants.WECHAT_TOKEN_REFRESH_AHEAD_SECONDS * 1000L;
            return System.currentTimeMillis() >= expireAtMillis - aheadMillis;
        }
    }
}
