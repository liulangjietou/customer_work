package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.AdminOpenApiClient;
import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelAccessProperties;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link WeChatConnectorRegistry} 注册、查询与条件注销（不依赖 Mockito）。
 */
class WeChatConnectorRegistryTest {

    private WeChatChannelConnector connector(String appKey, WeChatConnectorRegistry registry) {
        ChannelMessagePipeline pipeline = new ChannelMessagePipeline(
            new AdminOpenApiClient("http://127.0.0.1:9", "test-token"),
            new ChannelAccessProperties());
        ChannelRobot robot = new ChannelRobot(1L, ChannelAccessConstants.CHANNEL_TYPE_WECHAT, "微信客服",
            appKey, "secret", "callback-token", "agent-a",
            ChannelAccessConstants.SESSION_MODE_CONTINUOUS, 1L);
        WeChatAccessTokenClient tokenClient = new WeChatAccessTokenClient();
        WeChatCustomerMessageSender sender = new WeChatCustomerMessageSender(tokenClient);
        return new WeChatChannelConnector(robot, pipeline, registry, tokenClient, sender);
    }

    @Test
    void findReturnsRegisteredConnector() {
        WeChatConnectorRegistry registry = new WeChatConnectorRegistry();
        WeChatChannelConnector a = connector("wx-app-1", registry);
        registry.register("wx-app-1", a);
        assertSame(a, registry.find("wx-app-1"));
        assertNull(registry.find("missing"));
    }

    @Test
    void unregisterOnlyRemovesMatchingInstance() {
        WeChatConnectorRegistry registry = new WeChatConnectorRegistry();
        WeChatChannelConnector oldConn = connector("wx-app-1", registry);
        WeChatChannelConnector newConn = connector("wx-app-1", registry);

        registry.register("wx-app-1", oldConn);
        registry.register("wx-app-1", newConn);

        registry.unregister("wx-app-1", oldConn);
        assertSame(newConn, registry.find("wx-app-1"), "stale stop must not remove newer instance");

        registry.unregister("wx-app-1", newConn);
        assertNull(registry.find("wx-app-1"));
    }
}