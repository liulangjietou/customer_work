package com.richard.fyoung.customerchannel.access.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钉钉 externalUserId 生成规则测试：单聊=senderStaffId；群聊=conversationId#senderStaffId。
 * @author owlzhangfq@gmail.com
 */
class DingTalkExternalUserIdTest {

    @Test
    void singleChatUsesSenderStaffId() {
        assertEquals("userA",
            DingTalkStreamConnector.externalUserId("1", "convX", "userA"));
    }

    @Test
    void groupChatCombinesConversationAndSender() {
        assertEquals("groupConv#userA",
            DingTalkStreamConnector.externalUserId("2", "groupConv", "userA"));
    }

    @Test
    void unknownConversationTypeDefaultsToSingle() {
        assertEquals("userA",
            DingTalkStreamConnector.externalUserId("", "convX", "userA"));
    }
}
