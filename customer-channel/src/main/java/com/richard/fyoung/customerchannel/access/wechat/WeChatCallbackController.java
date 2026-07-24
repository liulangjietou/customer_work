package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信公众号回调入口（Spring MVC，与接入层同开关）。
 *
 * <p>路径 {@code /api/channels/wechat/{appId}/callback}，按 appId 路由到
 * {@link WeChatConnectorRegistry} 里注册的连接器：</p>
 * <ul>
 *   <li><b>GET</b>（接口配置验证）：验签通过原样返回 {@code echostr}，失败 403；</li>
 *   <li><b>POST</b>（消息推送，明文 XML）：验签 → 解析 XML → <b>立即返回 "success"</b>，
 *       实际处理（文本走管道、非文本回提示）异步执行，避免 5 秒超时触发微信重试；</li>
 * </ul>
 * <p>未注册的 appId 返回 404 并记 error 日志。整个 controller 随 {@code customer-channel.access.enabled}
 * 一起启停（与 {@link com.richard.fyoung.customerchannel.access.ChannelAccessConfiguration} 装配的
 * 注册表/工厂同生命周期）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@ConditionalOnProperty(prefix = "customer-channel.access", name = "enabled", havingValue = "true")
public class WeChatCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeChatCallbackController.class);

    private final WeChatConnectorRegistry registry;

    public WeChatCallbackController(WeChatConnectorRegistry registry) {
        this.registry = registry;
    }

    /**
     * 接口配置验证：微信在后台「服务器配置」保存时发起一次 GET，验签通过原样回显 echostr。
     */
    @GetMapping(value = "/api/channels/wechat/{appId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@PathVariable String appId,
                         @RequestParam(required = false) String signature,
                         @RequestParam(required = false) String timestamp,
                         @RequestParam(required = false) String nonce,
                         @RequestParam(required = false) String echostr,
                         HttpServletResponse response) {
        WeChatChannelConnector connector = registry.find(appId);
        if (connector == null) {
            log.error("wechat callback unknown appId on verify, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_UNKNOWN_APPID, appId);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "";
        }
        if (!WeChatSignatureVerifier.verify(connector.callbackToken(), timestamp, nonce, signature)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "";
        }
        return echostr == null ? "" : echostr;
    }

    /**
     * 消息推送：验签 → 解析 XML → 立即返回 success，处理异步执行。
     *
     * <p>无论文本与否都立即回 success；实际回复经客服消息 API 异步下发（见 {@link WeChatChannelConnector}）。
     * 验签失败返回 403 空体；未注册 appId 返回 404；XML 非法记 error 日志后仍回 success（微信重试也解析不了，
     * 避免无意义重试风暴）。</p>
     */
    @PostMapping(value = "/api/channels/wechat/{appId}/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String receive(@PathVariable String appId,
                          @RequestParam(required = false) String signature,
                          @RequestParam(required = false) String timestamp,
                          @RequestParam(required = false) String nonce,
                          @RequestBody(required = false) String body,
                          HttpServletResponse response) {
        WeChatChannelConnector connector = registry.find(appId);
        if (connector == null) {
            log.error("wechat callback unknown appId on receive, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_UNKNOWN_APPID, appId);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "";
        }
        if (!WeChatSignatureVerifier.verify(connector.callbackToken(), timestamp, nonce, signature)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "";
        }
        try {
            WeChatXmlMessage message = WeChatXmlMessage.parse(body);
            // 管道内部按会话串行异步处理，这里 submit 后立即返回 success
            connector.dispatch(message);
        } catch (Exception e) {
            log.error("wechat callback handle failed, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_CALLBACK_FAIL, appId, e);
        }
        return ChannelAccessConstants.WECHAT_CALLBACK_SUCCESS;
    }
}
