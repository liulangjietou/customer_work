package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.dingtalk.DingTalkStreamConnectorFactory;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;
import com.richard.fyoung.customerchannel.access.wechat.WeChatAccessTokenClient;
import com.richard.fyoung.customerchannel.access.wechat.WeChatConnectorFactory;
import com.richard.fyoung.customerchannel.access.wechat.WeChatConnectorRegistry;
import com.richard.fyoung.customerchannel.access.wechat.WeChatCustomerMessageSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 渠道接入层装配。仅当 {@code customer-channel.access.enabled=true} 时整层生效；
 * 默认关闭 → 零 Bean，不影响宿主启动，也与旧的钉钉演示（DingTalkChannelConfigurer）互不干扰。
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChannelAccessProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "customer-channel.access", name = "enabled", havingValue = "true")
public class ChannelAccessConfiguration {

    @Bean
    public AdminOpenApiClient adminOpenApiClient(ChannelAccessProperties properties) {
        return new AdminOpenApiClient(properties.getAdminBaseUrl(), properties.getAdminToken());
    }

    @Bean
    public ChannelMessagePipeline channelMessagePipeline(AdminOpenApiClient adminOpenApiClient,
                                                         ChannelAccessProperties properties) {
        return new ChannelMessagePipeline(adminOpenApiClient, properties);
    }

    /** 钉钉连接器工厂（第一个渠道实现）。新增企微/微信 = 再注册一个工厂 Bean。 */
    @Bean
    public DingTalkStreamConnectorFactory dingTalkStreamConnectorFactory(ChannelMessagePipeline pipeline) {
        return new DingTalkStreamConnectorFactory(pipeline);
    }

    // ===== 微信公众号（入站回调 + 客服消息）=====

    /** appId → 连接器注册表，供 WeChatCallbackController 与工厂共享。 */
    @Bean
    public WeChatConnectorRegistry weChatConnectorRegistry() {
        return new WeChatConnectorRegistry();
    }

    /** access_token 客户端（按 appId 缓存），整层共享一套。 */
    @Bean
    public WeChatAccessTokenClient weChatAccessTokenClient() {
        return new WeChatAccessTokenClient();
    }

    /** 客服消息发送器，复用 access_token 客户端。 */
    @Bean
    public WeChatCustomerMessageSender weChatCustomerMessageSender(WeChatAccessTokenClient tokenClient) {
        return new WeChatCustomerMessageSender(tokenClient);
    }

    /** 微信连接器工厂（第二个渠道实现）。 */
    @Bean
    public WeChatConnectorFactory weChatConnectorFactory(ChannelMessagePipeline pipeline,
                                                         WeChatConnectorRegistry registry,
                                                         WeChatAccessTokenClient tokenClient,
                                                         WeChatCustomerMessageSender messageSender) {
        return new WeChatConnectorFactory(pipeline, registry, tokenClient, messageSender);
    }

    @Bean
    public ChannelAccessManager channelAccessManager(List<ImChannelConnectorFactory> factories,
                                                     AdminOpenApiClient adminOpenApiClient,
                                                     ChannelAccessProperties properties) {
        return new ChannelAccessManager(factories, adminOpenApiClient, properties);
    }
}
