package com.richard.fyoung.customeradmin.auth.dto;

/**
 * 登录拼图 challenge。
 *
 * <p>目标横坐标是服务端秘密，响应只提供渲染所需的图片和尺寸。</p>
 */
public record LoginCaptchaChallengeResponse(
    String challengeId,
    int ttlSeconds,
    String backgroundImage,
    String puzzlePieceImage,
    int canvasWidth,
    int canvasHeight,
    int pieceWidth,
    int pieceHeight,
    int pieceY) {
}
