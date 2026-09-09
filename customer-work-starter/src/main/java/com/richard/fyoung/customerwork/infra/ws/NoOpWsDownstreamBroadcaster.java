package com.richard.fyoung.customerwork.infra.ws;

/**
 * 单副本部署的默认实现：不广播。
 *
 * <p>单副本时"本地找不到这个连接"就是"这个连接确实不在线"，没有别的副本可问。
 * 因此这里如实返回 false 而不是假装成功——调用方据此决定是落离线消息还是丢弃。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class NoOpWsDownstreamBroadcaster implements WsDownstreamBroadcaster {

    @Override
    public boolean broadcast(WsDownstreamTarget target, String id, String frameJson) {
        return false;
    }

    @Override
    public void subscribe(WsDownstreamDelivery delivery) {
        // 单副本无广播来源，无需订阅
    }
}
