package com.richard.fyoung.customerwork.safety.security;

/**
 * 终端用户登录态主体（从已验签 JWT 解析得到，请求内只读）。
 *
 * <p>由 {@link UserJwtService#verify(String)} 产出，经 {@link UserAuthWebFilter} 放入
 * {@code ServerWebExchange} 属性，供 {@code /api/customer/user/**} 控制器取用——控制器不再信任
 * 客户端自报的 userId，一律以此为准（避免越权操作他人工单）。</p>
 *
 * <p>{@code tenantId} 同理：登录时焊进令牌，后续请求一律以令牌里的为准，
 * 前端再传任何租户参数都不采信——否则改一个请求参数就能读别的租户的数据。</p>
 *
 * @param userId   用户业务 ID（JWT subject）
 * @param username 登录名
 * @param nickname 昵称
 * @param tenantId     归属租户
 * @param accessEpoch  签发时的租户访问版本；旧令牌无该值，访问门禁启用后按已撤销处理
 * @param sessionEpoch 签发时的用户会话版本；与 cw_user 不一致即撤销
 * @author owlzhangfq@gmail.com
 */
public record UserPrincipal(String userId, String username, String nickname,
                            String tenantId, Long accessEpoch, Long sessionEpoch) {

    /** 保留旧构造器供下游源码兼容；它表示没有绑定访问版本的历史主体。 */
    public UserPrincipal(String userId, String username, String nickname, String tenantId) {
        this(userId, username, nickname, tenantId, null, null);
    }

    /** 保留既有租户 epoch 构造器的源码兼容。 */
    public UserPrincipal(String userId, String username, String nickname,
                         String tenantId, Long accessEpoch) {
        this(userId, username, nickname, tenantId, accessEpoch, null);
    }
}
