package com.richard.fyoung.customerchannel.access.wechat;

import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * 微信明文模式推送的 XML 消息（只取本项目关心的字段）。
 *
 * <p>微信公众号消息为扁平 XML（字段值多为 CDATA），示例：</p>
 * <pre>{@code
 * <xml>
 *   <ToUserName><![CDATA[gh_xxx]]></ToUserName>
 *   <FromUserName><![CDATA[openid]]></FromUserName>
 *   <CreateTime>1700000000</CreateTime>
 *   <MsgType><![CDATA[text]]></MsgType>
 *   <Content><![CDATA[你好]]></Content>
 *   <MsgId>1234567890</MsgId>
 * </xml>
 * }</pre>
 * <p>用 JDK 内置 DOM 解析（零额外依赖），并关闭 DTD/外部实体防 XXE（接入层唯一一处防御）。</p>
 * @author owlzhangfq@gmail.com
 */
final class WeChatXmlMessage {

    private final String toUserName;
    private final String fromUserName;
    private final String msgType;
    private final String content;
    private final String msgId;

    private WeChatXmlMessage(String toUserName, String fromUserName, String msgType, String content, String msgId) {
        this.toUserName = toUserName;
        this.fromUserName = fromUserName;
        this.msgType = msgType;
        this.content = content;
        this.msgId = msgId;
    }

    /**
     * 解析微信明文 XML。
     *
     * @param xml 请求体
     * @return 解析结果
     * @throws IllegalArgumentException 解析失败（非法 XML）
     */
    static WeChatXmlMessage parse(String xml) {
        if (!StringUtils.hasText(xml)) {
            throw new IllegalArgumentException("wechat callback body is blank");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 关闭 DTD/外部实体，防 XXE
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();
            return new WeChatXmlMessage(
                text(doc, "ToUserName"),
                text(doc, "FromUserName"),
                text(doc, "MsgType"),
                text(doc, "Content"),
                text(doc, "MsgId"));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid wechat callback xml", e);
        }
    }

    /** 取首个同名元素的文本内容（不存在返回空串）。 */
    private static String text(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null ? "" : value.trim();
    }

    boolean isText() {
        return ChannelAccessConstants.WECHAT_MSG_TYPE_TEXT.equalsIgnoreCase(msgType);
    }

    String getToUserName() {
        return toUserName;
    }

    String getFromUserName() {
        return fromUserName;
    }

    String getMsgType() {
        return msgType;
    }

    String getContent() {
        return content;
    }

    String getMsgId() {
        return msgId;
    }
}
