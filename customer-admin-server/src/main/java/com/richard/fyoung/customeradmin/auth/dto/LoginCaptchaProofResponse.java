package com.richard.fyoung.customeradmin.auth.dto;

/** 登录拼图核验通过后签发的一次性 proof。 */
public record LoginCaptchaProofResponse(String proof, int ttlSeconds) {
}
