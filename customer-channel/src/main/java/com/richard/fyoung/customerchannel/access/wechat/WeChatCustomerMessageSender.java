package com.richard.fyoung.customerchannel.access.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.support.WebClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信客服消息发送器（主动推送文本给用户）。
 *
 * <p>{@code POST /cgi-bin/message/custom/send?access_token=} body
 * {@code {"touser":openid,"msgtype":"text","text":{"content":...}}}。回调必须 5 秒内应答，故对话结果
 * 走这里异步推送（客服消息 48 小时窗口内可主动下发）。超长回复按 ~1000 字符分段多条发送；access_token
 * 失效（errcode 40001/42001）时强刷一次重试；最终 errcode 非 0 记 error 日志（带错误码占位符）。</p>
 * @author owlzhangfq@gmail.com
 */
public class WeChatCustomerMessageSender {

    private static final Logger log = LoggerFactory.getLogger(WeChatCustomerMessageSender.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final WeChatAccessTokenClient tokenClient;

    public WeChatCustomerMessageSender(WeChatAccessTokenClient tokenClient) {
        this(WebClients.builder().baseUrl(ChannelAccessConstants.WECHAT_API_BASE_URL).build(), tokenClient);
    }

    /** 仅供测试注入自定义 baseUrl 的 WebClient。 */
    WeChatCustomerMessageSender(WebClient webClient, WeChatAccessTokenClient tokenClient) {
        this.webClient = webClient;
        this.tokenClient = tokenClient;
    }

    /**
     * 发送文本客服消息（超长自动分段多条）。
     *
     * @param appId   公众号 AppID
     * @param secret  公众号 AppSecret
     * @param openId  接收用户 openid
     * @param content 文本内容（已降级为纯文本）
     */
    public void send(String appId, String secret, String openId, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        for (String segment : segments(content, ChannelAccessConstants.WECHAT_SEGMENT_MAX_CHARS)) {
            sendSegment(appId, secret, openId, segment);
        }
    }

    /** 单段发送：先用缓存 token，遇 token 失效错误码强刷一次重试。 */
    private void sendSegment(String appId, String secret, String openId, String segment) {
        try {
            String token = tokenClient.getToken(appId, secret);
            int errcode = post(token, openId, segment);
            if (isTokenInvalid(errcode)) {
                token = tokenClient.forceRefresh(appId, secret);
                errcode = post(token, openId, segment);
            }
            if (errcode != 0) {
                log.error("wechat custom message send failed, code={}, appId={}, errcode={}",
                    ChannelAccessConstants.CODE_WECHAT_SEND_FAIL, appId, errcode);
            }
        } catch (Exception e) {
            log.error("wechat custom message send error, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_SEND_FAIL, appId, e);
        }
    }

    private boolean isTokenInvalid(int errcode) {
        return errcode == ChannelAccessConstants.WECHAT_ERRCODE_INVALID_TOKEN
            || errcode == ChannelAccessConstants.WECHAT_ERRCODE_EXPIRED_TOKEN;
    }

    /** 实际 POST 一条，返回微信 errcode（缺省 0 表示成功）。 */
    private int post(String accessToken, String openId, String content) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("content", content);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("touser", openId);
        payload.put("msgtype", ChannelAccessConstants.WECHAT_MSG_TYPE_TEXT);
        payload.put("text", text);
        JsonNode root = webClient.post()
            .uri(uri -> uri.path(ChannelAccessConstants.WECHAT_PATH_CUSTOM_SEND)
                .queryParam("access_token", accessToken).build())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(REQUEST_TIMEOUT);
        return root == null ? 0 : root.path("errcode").asInt(0);
    }

    /**
     * 按最大长度切分文本（按字符切，保证每段 ≤ max）。静态无副作用，便于单测。
     */
    static List<String> segments(String content, int max) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return result;
        }
        int limit = max <= 0 ? content.length() : max;
        for (int start = 0; start < content.length(); start += limit) {
            result.add(content.substring(start, Math.min(start + limit, content.length())));
        }
        return result;
    }
}
