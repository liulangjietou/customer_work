package com.richard.fyoung.customeradmin.auth.dto;

/**
 * 登录响应：{@code forceChangePassword=true} 时前端应拦截跳转改密页
 * （账号当前密码仍等于初始化种子哈希值，见 {@code AuthService#login}）。
 * @author owlzhangfq@gmail.com
 */
public record LoginResponse(String token, String nickname, boolean forceChangePassword,
                            String approvalStatus, String approvalRemark) {
}
