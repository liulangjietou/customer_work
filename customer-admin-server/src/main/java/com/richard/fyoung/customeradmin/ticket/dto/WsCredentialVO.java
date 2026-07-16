package com.richard.fyoung.customeradmin.ticket.dto;

/**
 * 坐席 WS 接入凭证：客服浏览器凭 {@code token} 直连 8080 的 {@code wsUrl}（/ws/agent）。
 *
 * @param token       HMAC 令牌（{@code credentialExpireHours} 有效）
 * @param wsUrl       WebSocket 接入地址
 * @param expiresAtMs 过期时间戳（毫秒）
 * @param agentId     坐席标识（当前登录名）
 * @author owlzhangfq@gmail.com
 */
public record WsCredentialVO(String token, String wsUrl, long expiresAtMs, String agentId) {
}
