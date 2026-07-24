package com.richard.fyoung.customerchannel.access.spi;

/**
 * IM 渠道连接器 SPI —— 一个实例管理一台机器人的一条平台连接。
 *
 * <p>钉钉是第一个实现（Stream 模式 WebSocket 出站连接）；后续接入企业微信/微信只需新增
 * 一个实现类 + 对应的 {@link ImChannelConnectorFactory}，无需改动 {@code ChannelAccessManager}
 * 与消息管道。连接器负责：建立/断开平台连接、把原始消息解析成归一化结构交给管道、
 * 提供平台特定的回复通道。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ImChannelConnector {

    /** 渠道类型（dingtalk/wecom/...），与机器人配置的 channelType 对应。 */
    String channelType();

    /** 建立连接并开始收消息。实现须自兜底，异常不得逸出到宿主启动流程。 */
    void start();

    /** 优雅停止连接，释放资源。可重复调用。 */
    void stop();

    /** 是否处于运行中。 */
    boolean isRunning();
}
