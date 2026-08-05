package com.richard.fyoung.customerwork.security;

/**
 * 目标地址被 {@link HttpTargetGuard} 的安全策略拦截（URL 非法、协议不支持、host 不在白名单、
 * 解析出的 IP 命中内网/链路本地）。
 *
 * <p>继承 {@link IllegalArgumentException} 而非另起一支：对调用方而言这本就是"入参地址不可用"，
 * 沿用 starter 一贯的 {@code IllegalArgumentException} 约定，只按业务模块转译一次错误码即可
 * （admin 侧转 {@code BizException}）。单独立类是为了让调用方能把"被安全策略拦截"与
 * "参数格式非法"分开转译成不同的业务错误码。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class HttpTargetForbiddenException extends IllegalArgumentException {

    public HttpTargetForbiddenException(String message) {
        super(message);
    }
}
