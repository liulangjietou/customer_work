package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelAccessProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

/**
 * 微信公众号回调唯一安全入口：时间窗、签名/AES、nonce 回放保护和消息幂等均在分发前完成。
 *
 * <p>明文模式用于兼容旧配置；安全模式校验 {@code msg_signature} 后解密，并核对密文内 AppID。
 * POST 对合法重复投递始终返回 {@code success}，但不会重复触发智能体。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@ConditionalOnProperty(prefix = "customer-channel.access", name = "enabled", havingValue = "true")
public class WeChatCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeChatCallbackController.class);

    private final WeChatConnectorRegistry registry;
    private final WeChatReplayGuard replayGuard;
    private final Clock clock;
    private final Duration timestampTolerance;
    private final Duration nonceTtl;
    private final Duration messageTtl;

    @Autowired
    public WeChatCallbackController(WeChatConnectorRegistry registry,
                                    WeChatReplayGuard replayGuard,
                                    ChannelAccessProperties properties) {
        this(registry, replayGuard, properties, Clock.systemUTC());
    }

    WeChatCallbackController(WeChatConnectorRegistry registry,
                             WeChatReplayGuard replayGuard,
                             ChannelAccessProperties properties,
                             Clock clock) {
        ChannelAccessProperties.WeChat wechat = properties.getWechat();
        requirePositive(wechat.getTimestampToleranceSeconds(), "timestampToleranceSeconds");
        requirePositive(wechat.getNonceTtlSeconds(), "nonceTtlSeconds");
        requirePositive(wechat.getMessageTtlSeconds(), "messageTtlSeconds");
        this.registry = registry;
        this.replayGuard = replayGuard;
        this.clock = clock;
        this.timestampTolerance = Duration.ofSeconds(wechat.getTimestampToleranceSeconds());
        this.nonceTtl = Duration.ofSeconds(wechat.getNonceTtlSeconds());
        this.messageTtl = Duration.ofSeconds(wechat.getMessageTtlSeconds());
    }

    /** 接口配置验证：明文模式回显原文，安全模式验签并解密 echostr 后返回。 */
    @GetMapping(value = "/api/channels/wechat/{appId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@PathVariable String appId,
                         @RequestParam(required = false) String signature,
                         @RequestParam(name = "msg_signature", required = false) String messageSignature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr,
                         HttpServletResponse response) {
        WeChatChannelConnector connector = findConnector(appId, response, "verify");
        if (connector == null) {
            return "";
        }
        if (!isFresh(timestamp, nonce)) {
            return forbidden(response);
        }
        try {
            String echo;
            if (isSafeMode(connector)) {
                if (!WeChatSignatureVerifier.verifySafe(connector.callbackToken(), timestamp, nonce,
                    echostr, messageSignature)) {
                    return forbidden(response);
                }
                echo = WeChatMessageCrypto.decrypt(connector.encodingAesKey(), echostr, appId);
            } else {
                if (!WeChatSignatureVerifier.verify(connector.callbackToken(), timestamp, nonce, signature)) {
                    return forbidden(response);
                }
                echo = echostr == null ? "" : echostr;
            }
            if (!claimNonce(appId, nonce)) {
                return forbidden(response);
            }
            return echo;
        } catch (ReplayStoreUnavailableException e) {
            return replayStoreUnavailable(appId, response, e.getCause());
        } catch (IllegalArgumentException e) {
            return forbidden(response);
        }
    }

    /** 消息推送：合法重复请求返回 success；首次消息才进入智能体管道。 */
    @PostMapping(value = "/api/channels/wechat/{appId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receive(@PathVariable String appId,
                          @RequestParam(required = false) String signature,
                          @RequestParam(name = "msg_signature", required = false) String messageSignature,
                          @RequestParam(required = false) String timestamp,
                          @RequestParam(required = false) String nonce,
                          @RequestBody(required = false) String body,
                          HttpServletResponse response) {
        WeChatChannelConnector connector = findConnector(appId, response, "receive");
        if (connector == null) {
            return "";
        }
        if (!isFresh(timestamp, nonce)) {
            return forbidden(response);
        }
        boolean safeMode = isSafeMode(connector);
        String messageXml;
        try {
            if (safeMode) {
                String encrypted = WeChatXmlMessage.encryptedPayload(body);
                if (!WeChatSignatureVerifier.verifySafe(connector.callbackToken(), timestamp, nonce,
                    encrypted, messageSignature)) {
                    return forbidden(response);
                }
                messageXml = WeChatMessageCrypto.decrypt(connector.encodingAesKey(), encrypted, appId);
            } else {
                if (!WeChatSignatureVerifier.verify(connector.callbackToken(), timestamp, nonce, signature)) {
                    return forbidden(response);
                }
                messageXml = body;
            }
            if (!claimNonce(appId, nonce)) {
                return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
            }
        } catch (ReplayStoreUnavailableException e) {
            return replayStoreUnavailable(appId, response, e.getCause());
        } catch (IllegalArgumentException e) {
            if (safeMode) {
                return forbidden(response);
            }
            return malformedPlaintext(appId, e);
        }

        try {
            WeChatXmlMessage message = WeChatXmlMessage.parse(messageXml);
            String messageKey = message.idempotencyKey();
            if (!StringUtils.hasText(messageKey)) {
                log.error("wechat callback missing stable message key, code={}, appId={}",
                    ChannelAccessConstants.CODE_WECHAT_CALLBACK_FAIL, appId);
                return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
            }
            if (!claimMessage(appId, messageKey)) {
                return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
            }
            connector.dispatch(message);
            return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
        } catch (ReplayStoreUnavailableException e) {
            return replayStoreUnavailable(appId, response, e.getCause());
        } catch (IllegalArgumentException e) {
            if (safeMode) {
                return forbidden(response);
            }
            return malformedPlaintext(appId, e);
        } catch (RuntimeException e) {
            log.error("wechat callback handle failed, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_CALLBACK_FAIL, appId, e);
            return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
        }
    }

    private WeChatChannelConnector findConnector(String appId, HttpServletResponse response, String action) {
        WeChatChannelConnector connector = registry.find(appId);
        if (connector == null) {
            log.error("wechat callback unknown appId, code={}, action={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_UNKNOWN_APPID, action, appId);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
        return connector;
    }

    private boolean isSafeMode(WeChatChannelConnector connector) {
        return ChannelAccessConstants.WECHAT_CALLBACK_MODE_SAFE.equalsIgnoreCase(connector.callbackMode());
    }

    private boolean isFresh(String timestamp, String nonce) {
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)) {
            return false;
        }
        try {
            long requestEpochSecond = Long.parseLong(timestamp);
            long nowEpochSecond = clock.instant().getEpochSecond();
            long toleranceSeconds = timestampTolerance.getSeconds();
            return requestEpochSecond >= nowEpochSecond - toleranceSeconds
                && requestEpochSecond <= nowEpochSecond + toleranceSeconds;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean claimNonce(String appId, String nonce) {
        try {
            return replayGuard.claimNonce(appId, nonce, nonceTtl);
        } catch (RuntimeException e) {
            throw new ReplayStoreUnavailableException(e);
        }
    }

    private boolean claimMessage(String appId, String messageKey) {
        try {
            return replayGuard.claimMessage(appId, messageKey, messageTtl);
        } catch (RuntimeException e) {
            throw new ReplayStoreUnavailableException(e);
        }
    }

    private String forbidden(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "";
    }

    private String malformedPlaintext(String appId, IllegalArgumentException e) {
        log.error("wechat callback handle failed, code={}, appId={}",
            ChannelAccessConstants.CODE_WECHAT_CALLBACK_FAIL, appId, e);
        return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
    }

    private String replayStoreUnavailable(String appId, HttpServletResponse response, Throwable e) {
        log.error("wechat callback replay store failed, code={}, appId={}",
            ChannelAccessConstants.CODE_WECHAT_REPLAY_STORE_FAIL, appId, e);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        return "";
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("wechat " + name + " must be positive");
        }
    }

    private static final class ReplayStoreUnavailableException extends RuntimeException {
        private ReplayStoreUnavailableException(RuntimeException cause) {
            super(cause);
        }
    }
}
