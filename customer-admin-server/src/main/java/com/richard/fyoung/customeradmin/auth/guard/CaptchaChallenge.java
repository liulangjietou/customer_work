package com.richard.fyoung.customeradmin.auth.guard;

/**
 * 一次验证码挑战：前端拿 {@code image} 直接塞进 {@code <img src>}，
 * 提交注册时回传 {@code captchaId} 与用户输入。
 *
 * @param captchaId  服务端校验凭据，与图片一一对应
 * @param image      PNG 的 data URI，省掉一次图片请求，也避免验证码图片被单独缓存
 * @param ttlSeconds 有效期，前端据此提示"点击刷新"
 * @author owlzhangfq@gmail.com
 */
public record CaptchaChallenge(String captchaId, String image, int ttlSeconds) {
}
