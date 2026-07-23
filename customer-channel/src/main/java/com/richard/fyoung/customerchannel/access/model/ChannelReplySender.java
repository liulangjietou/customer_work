package com.richard.fyoung.customerchannel.access.model;

/**
 * 渠道回复发送器（由连接器提供平台特定实现）。
 *
 * <p>钉钉实现 = 向本条消息回调里的 sessionWebhook POST markdown；企微/微信实现各自不同。
 * 管道只依赖此函数式接口，不关心回复走哪个平台通道。</p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface ChannelReplySender {

    /**
     * 回复一段文本给用户。
     *
     * @param text 回复正文
     */
    void send(String text);
}
