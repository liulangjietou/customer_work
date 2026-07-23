package com.richard.fyoung.customerchannel.access.dingtalk;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;
import com.richard.fyoung.customerchannel.access.support.WebClients;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 钉钉连接器工厂。为每台钉钉机器人创建一个 {@link DingTalkStreamConnector}。
 *
 * <p>回复用的 {@link WebClient} 无 baseUrl（sessionWebhook 是绝对地址），工厂内共享一个即可。</p>
 * @author owlzhangfq@gmail.com
 */
public class DingTalkStreamConnectorFactory implements ImChannelConnectorFactory {

    private final ChannelMessagePipeline pipeline;
    private final WebClient replyWebClient;

    public DingTalkStreamConnectorFactory(ChannelMessagePipeline pipeline) {
        this.pipeline = pipeline;
        this.replyWebClient = WebClients.builder().build();
    }

    @Override
    public String channelType() {
        return ChannelAccessConstants.CHANNEL_TYPE_DINGTALK;
    }

    @Override
    public ImChannelConnector create(ChannelRobot robot) {
        return new DingTalkStreamConnector(robot, pipeline, replyWebClient);
    }
}
