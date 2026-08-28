package com.richard.fyoung.customeradmin.auth.dto;

/** 登录滑块 challenge。 */
public record LoginCaptchaChallengeResponse(String challengeId, int ttlSeconds) {
}
