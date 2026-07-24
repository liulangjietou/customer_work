package com.richard.fyoung.customerchannel.access.wechat;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 微信连接器注册表（appId → 连接器）。
 *
 * <p>微信是<b>入站回调</b>模型：{@link WeChatCallbackController} 收到某 appId 的回调后，要按 appId 找到
 * 对应的连接器来完成签名校验与消息分发。而连接器由 {@link WeChatConnectorFactory} 在
 * {@code ChannelAccessManager} 拉到机器人配置时创建/启停。二者通过本注册表解耦：连接器 start 时注册、
 * stop 时注销；controller 只读。作为单例 Bean 供两侧共享。</p>
 * @author owlzhangfq@gmail.com
 */
public class WeChatConnectorRegistry {

    /** appId → 运行中的微信连接器。 */
    private final ConcurrentMap<String, WeChatChannelConnector> connectors = new ConcurrentHashMap<>();

    void register(String appId, WeChatChannelConnector connector) {
        connectors.put(appId, connector);
    }

    /** 仅当当前注册的正是该连接器实例时才注销（避免 restart 时新实例被旧实例的 stop 误删）。 */
    void unregister(String appId, WeChatChannelConnector connector) {
        connectors.remove(appId, connector);
    }

    /** 按 appId 查连接器，未注册返回 {@code null}。 */
    public WeChatChannelConnector find(String appId) {
        return connectors.get(appId);
    }
}
