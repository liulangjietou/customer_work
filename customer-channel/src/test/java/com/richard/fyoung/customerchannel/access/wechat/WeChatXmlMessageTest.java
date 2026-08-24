package com.richard.fyoung.customerchannel.access.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeChatXmlMessage} 明文 XML 解析测试：文本消息字段解析、非文本判定、非法 XML 抛错。
 * @author owlzhangfq@gmail.com
 */
class WeChatXmlMessageTest {

    @Test
    void shouldParseTextMessage() {
        String xml = "<xml>"
            + "<ToUserName><![CDATA[gh_public]]></ToUserName>"
            + "<FromUserName><![CDATA[openid-123]]></FromUserName>"
            + "<CreateTime>1700000000</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好，客服]]></Content>"
            + "<MsgId>1234567890123456</MsgId>"
            + "</xml>";

        WeChatXmlMessage msg = WeChatXmlMessage.parse(xml);

        assertEquals("gh_public", msg.getToUserName());
        assertEquals("openid-123", msg.getFromUserName());
        assertEquals("text", msg.getMsgType());
        assertEquals("你好，客服", msg.getContent());
        assertEquals("1234567890123456", msg.getMsgId());
        assertEquals("msgid:1234567890123456", msg.idempotencyKey());
        assertTrue(msg.isText());
    }

    @Test
    void shouldDetectNonTextMessage() {
        String xml = "<xml>"
            + "<FromUserName><![CDATA[openid-9]]></FromUserName>"
            + "<MsgType><![CDATA[image]]></MsgType>"
            + "<MsgId>777</MsgId>"
            + "</xml>";

        WeChatXmlMessage msg = WeChatXmlMessage.parse(xml);

        assertFalse(msg.isText());
        assertEquals("image", msg.getMsgType());
    }

    @Test
    void shouldThrowOnInvalidXml() {
        assertThrows(IllegalArgumentException.class, () -> WeChatXmlMessage.parse("not-xml <<<"));
        assertThrows(IllegalArgumentException.class, () -> WeChatXmlMessage.parse(""));
    }

    @Test
    void eventWithoutMsgIdShouldBuildStableFallbackKey() {
        String xml = "<xml>"
            + "<FromUserName>openid-1</FromUserName>"
            + "<CreateTime>1700000000</CreateTime>"
            + "<MsgType>event</MsgType>"
            + "<Event>subscribe</Event>"
            + "<EventKey>qrscene_42</EventKey>"
            + "</xml>";

        WeChatXmlMessage message = WeChatXmlMessage.parse(xml);

        assertEquals("fallback:openid-1\n1700000000\nevent\nsubscribe\nqrscene_42\n",
            message.idempotencyKey());
    }

    @Test
    void messageWithoutStableFieldsShouldNotBeDispatchable() {
        WeChatXmlMessage message = WeChatXmlMessage.parse("<xml><MsgType>event</MsgType></xml>");

        assertEquals("", message.idempotencyKey());
    }

    @Test
    void shouldParseEncryptedOuterPayload() {
        assertEquals("cipher-text", WeChatXmlMessage.encryptedPayload(
            "<xml><Encrypt><![CDATA[cipher-text]]></Encrypt></xml>"));
        assertThrows(IllegalArgumentException.class,
            () -> WeChatXmlMessage.encryptedPayload("<xml></xml>"));
    }
}
