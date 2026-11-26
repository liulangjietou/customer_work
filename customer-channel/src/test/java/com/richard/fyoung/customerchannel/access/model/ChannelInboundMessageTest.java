package com.richard.fyoung.customerchannel.access.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ChannelInboundMessage#serialKey()} 串行键契约。
 */
class ChannelInboundMessageTest {

    @Test
    void serialKeyCombinesChannelAppAndExternalUser() {
        ChannelInboundMessage msg = new ChannelInboundMessage(
            "wechat", "wx-app", "agent-a", "continuous", "openid-9", true, "hi");
        assertEquals("wechat|wx-app|openid-9", msg.serialKey());
    }
}