package com.richard.fyoung.customerchannel.access;

import com.richard.fyoung.customerchannel.access.dingtalk.DingTalkStreamConnectorFactory;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnectorFactory;
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

    @Bean
    public ChannelAccessManager channelAccessManager(List<ImChannelConnectorFactory> factories,
                                                     AdminOpenApiClient adminOpenApiClient,
                                                     ChannelAccessProperties properties) {
        return new ChannelAccessManager(factories, adminOpenApiClient, properties);
    }
}
