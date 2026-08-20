package com.richard.fyoung.customerworkapp.ws;

/**
 * WebSocket 接入层公共字面量（帧内字段名见 {@code WsFrame}，那才是帧协议的定义处）。
 *
 * @author owlzhangfq@gmail.com
 */
public final class WsConstants {

    /**
     * 握手 URL 上携带令牌的查询参数名。
     *
     * <p>浏览器的 WebSocket API 不支持自定义请求头，令牌只能挂在 URL 上；
     * 用户端 {@code /ws/user} 与坐席端 {@code /ws/agent} 两条路径必须用同一个参数名。</p>
     */
    public static final String QUERY_TOKEN = "token";

    private WsConstants() {
    }
}
