package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器注册：除登录/文档/运维端点外，全部 {@code /api/**} 要求已登录。
 *
 * <p>接口级权限点校验（{@code @SaCheckPermission}）标注在具体 Controller 写/查方法上，
 * 本拦截器只做登录态这一层兜底，两者互补（对齐需求文档"所有写操作校验当前用户资源权限"）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()))
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/login",
                "/api/auth/login-captcha/challenge",
                "/api/auth/login-captcha/verify",
                "/api/auth/register",
                // 注册页在未登录状态下就要取验证码图片与"本实例开不开放注册"，
                // 两者都不含敏感信息，防滥用由 RegistrationGuard 的 IP 限流负责
                "/api/auth/register-options",
                "/api/auth/captcha",
                // 注册邮箱验证码：登录前的匿名接口。它会真的发出一封邮件，
                // 防滥用由图形验证码 + IP 限流 + 单邮箱冷却与日限四道负责（见 EmailVerificationService）
                "/api/auth/email-code",
                "/api/auth/sso-login",
                // 内网工作台脚本回调：ScriptCat 脚本运行在目标站点页面里，拿不到后台登录态，
                // 改用个人访问令牌鉴权（见 WorkbenchAgentController，自行校验 X-Workbench-Token）
                "/api/workbench/agent/**",
                // 菜单图标图片：前端用 <img src="..."> 直接加载，浏览器不会带 axios 注入的
                // Authorization 头，这类静态资源必须放行，鉴权收敛到"谁能上传"这一侧
                // （见 MenuAdminController 的 @SaCheckPermission("menu:edit")）。
                "/api/menu-icons/**",
                // 登录页轮播图：登录页未登录即要实时拉取图片列表 + <img> 直接加载图片文件，
                // 整个前缀放行（含 /list 读接口），鉴权收敛到"谁能上传/管理"一侧
                // （见 LoginImageAdminController 的 @SaCheckPermission("login-image:*")）。
                "/api/login-images/**",
                // 开放 API：供 customer-channel 等外部模块调用（钉钉/企微机器人接入），走 X-Open-Api-Token
                // 令牌鉴权而非后台登录态，整个前缀放行，鉴权收敛到 OpenApiAuthInterceptor 一侧
                // （见 OpenApiWebConfig / OpenApiAuthInterceptor）。
                "/api/open/**",
                "/actuator/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/webjars/**");
    }
}
