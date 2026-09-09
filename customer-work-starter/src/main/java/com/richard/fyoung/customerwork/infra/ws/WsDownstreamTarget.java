package com.richard.fyoung.customerwork.infra.ws;

/**
 * 下行推送的目标类型。
 *
 * <p>用户与坐席是两套 ID 空间，同一个 ID 值指的是不同的人——
 * 混用会把消息投递给错误的对象，这与主体配额那边 {@code USER} 与 {@code ADMIN_USER}
 * 必须分开是同一个理由。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public enum WsDownstreamTarget {

    /** 终端用户连接。 */
    USER,

    /** 坐席连接。 */
    AGENT
}
