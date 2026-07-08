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
                "/actuator/**",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/webjars/**");
    }
}
