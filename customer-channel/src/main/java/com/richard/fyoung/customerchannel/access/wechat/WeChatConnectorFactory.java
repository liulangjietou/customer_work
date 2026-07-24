package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;

/**
 * 微信公众号连接器工厂。为每台微信机器人创建一个 {@link WeChatChannelConnector}。
 *
 * <p>token 客户端与客服消息发送器按 appId 内部缓存/寻址，故整个工厂共享一套即可；连接器与
 * {@link WeChatCallbackController} 通过 {@link WeChatConnectorRegistry} 解耦。</p>
 * @author owlzhangfq@gmail.com
 */
public class WeChatConnectorFactory implements ImChannelConnectorFactory {

    private final ChannelMessagePipeline pipeline;
    private final WeChatConnectorRegistry registry;
    private final WeChatAccessTokenClient tokenClient;
    private final WeChatCustomerMessageSender messageSender;

    public WeChatConnectorFactory(ChannelMessagePipeline pipeline, WeChatConnectorRegistry registry,
                                  WeChatAccessTokenClient tokenClient, WeChatCustomerMessageSender messageSender) {
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
    public ImChannelConnector create(ChannelRobot robot) {
        return new WeChatChannelConnector(robot, pipeline, registry, tokenClient, messageSender);
    }
}
