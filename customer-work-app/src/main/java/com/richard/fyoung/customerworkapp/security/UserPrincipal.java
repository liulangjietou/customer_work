package com.richard.fyoung.customerworkapp.security;

/**
 * 终端用户登录态主体（从已验签 JWT 解析得到，请求内只读）。
 *
 * <p>由 {@link UserJwtService#verify(String)} 产出，经 {@link UserAuthWebFilter} 放入
 * {@code ServerWebExchange} 属性，供 {@code /api/customer/user/**} 控制器取用——控制器不再信任
 * 客户端自报的 userId，一律以此为准（避免越权操作他人工单）。</p>
 *
 * @param userId   用户业务 ID（JWT subject）
 * @param username 登录名
 * @param nickname 昵称
 * @author owlzhangfq@gmail.com
 */
public record UserPrincipal(String userId, String username, String nickname) {
}
