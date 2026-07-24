package com.richard.fyoung.customerchannel.access.spi;

import com.richard.fyoung.customerchannel.access.model.ChannelRobot;

/**
 * 渠道连接器工厂 SPI —— 按渠道类型为某台机器人创建一个 {@link ImChannelConnector} 实例。
 *
 * <p>{@code ChannelAccessManager} 注入容器里所有工厂 Bean，按 {@link #channelType()} 建索引；
 * 拉到机器人后用对应工厂 {@link #create(ChannelRobot)} 出连接器。新增渠道 = 新增一个工厂实现。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ImChannelConnectorFactory {

    /** 本工厂负责的渠道类型。 */
    String channelType();

    /**
     * 为给定机器人创建一个连接器实例（尚未 start）。
     *
     * @param robot 机器人配置快照
     * @return 绑定该机器人的连接器
     */
    ImChannelConnector create(ChannelRobot robot);
}
