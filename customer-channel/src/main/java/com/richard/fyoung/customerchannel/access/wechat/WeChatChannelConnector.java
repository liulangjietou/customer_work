package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelInboundMessage;
import com.richard.fyoung.customerchannel.access.model.ChannelReplySender;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import com.richard.fyoung.customerchannel.access.support.BoundedIdDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信公众号连接器（渠道接入层第二个实现）。
 *
 * <p>与钉钉 Stream（出站 WebSocket）不同，微信是<b>入站回调</b>：微信服务器把用户消息 POST 到
 * {@link WeChatCallbackController}，controller 按 appId 找到本连接器完成签名校验与分发；回复不能走回调响应
 * （必须 5 秒内返回 success），而是异步经<b>客服消息 API</b>主动推送。</p>
 *
 * <ul>
 *   <li>externalUserId = 微信 openid（FromUserName），公众号无群聊概念；</li>
 *   <li>MsgId 去重防微信重试；文本走统一管道，非文本由管道回「目前只支持文本消息」；</li>
 *   <li>回复器：openid → {@link WeChatTextFormatter} 降级为纯文本 → {@link WeChatCustomerMessageSender} 推送。</li>
 * </ul>
 * <p>本连接器不持有平台长连接，start/stop 只做注册表登记；sessionMode 由管道处理，无需特殊逻辑。</p>
 * @author owlzhangfq@gmail.com
 */
public class WeChatChannelConnector implements ImChannelConnector {

    private static final Logger log = LoggerFactory.getLogger(WeChatChannelConnector.class);

    private final ChannelRobot robot;
    private final ChannelMessagePipeline pipeline;
    private final WeChatConnectorRegistry registry;
    private final WeChatAccessTokenClient tokenClient;
    private final WeChatCustomerMessageSender messageSender;
    private final BoundedIdDeduplicator deduplicator =
        new BoundedIdDeduplicator(ChannelAccessConstants.WECHAT_MSGID_DEDUP_CAPACITY);

    private volatile boolean running;

    public WeChatChannelConnector(ChannelRobot robot, ChannelMessagePipeline pipeline,
                                  WeChatConnectorRegistry registry, WeChatAccessTokenClient tokenClient,
                                  WeChatCustomerMessageSender messageSender) {
        this.robot = robot;
        this.pipeline = pipeline;
        this.registry = registry;
        this.tokenClient = tokenClient;
        this.messageSender = messageSender;
    }

    @Override
    public String channelType() {
        return ChannelAccessConstants.CHANNEL_TYPE_WECHAT;
    }

    @Override
    public void start() {
        registry.register(robot.getAppKey(), this);
        running = true;
        // 预热 access_token：失败只记日志，不影响连接器可用（首条消息回复时会自然重试）
        try {
            tokenClient.getToken(robot.getAppKey(), robot.getAppSecret());
        } catch (Exception e) {
            log.error("wechat access_token prewarm failed, code={}, appId={}",
                ChannelAccessConstants.CODE_WECHAT_TOKEN_FAIL, robot.getAppKey(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        registry.unregister(robot.getAppKey(), this);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 回调 Token（微信语义下 robotCode = 公众平台配置的 Token），供 controller 验签。 */
    public String callbackToken() {
        return robot.getRobotCode();
    }

    /**
     * 分发一条已解析的入站消息到管道（供 controller 调用，立即返回；实际处理由管道异步串行执行）。
     *
     * <p>先按 MsgId 去重（微信重试防重复触发），再构造归一化消息与客服消息回复器交给管道。</p>
     */
    public void dispatch(WeChatXmlMessage message) {
        if (!deduplicator.firstSeen(message.getMsgId())) {
            // 重复投递（微信重试）：直接丢弃，不重复回复
            return;
        }
        String openId = message.getFromUserName();
        ChannelInboundMessage inbound = new ChannelInboundMessage(
            ChannelAccessConstants.CHANNEL_TYPE_WECHAT, robot.getAppKey(), robot.getAgentCode(),
            robot.getSessionMode(), openId, message.isText(), message.getContent());
        ChannelReplySender reply = replyText ->
            messageSender.send(robot.getAppKey(), robot.getAppSecret(), openId,
                WeChatTextFormatter.format(replyText));
        pipeline.submit(inbound, reply);
    }
}
