package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.system.loginimage.service.LoginImageStorageService;
import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地磁盘静态资源映射：菜单管理"上传图片图标"落盘到 {@code ./data/menu}
 * （项目里没有 OSS/MinIO，沿用现有本地磁盘约定），这里把它挂到 {@code /api/menu-icons/**}
 * 对外提供访问——落在 {@code /api} 前缀下是为了复用前端 Vite 代理规则（只代理 {@code /api}），
 * 不用额外加一条代理配置。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/menu-icons/**")
            .addResourceLocations("file:" + MenuIconStorageService.ICON_ROOT + "/");
        // 登录页轮播图：同一套本地磁盘约定，/list 走 Controller（映射优先级高于静态资源），其余按文件名取图
        registry.addResourceHandler("/api/login-images/**")
            .addResourceLocations("file:" + LoginImageStorageService.IMAGE_ROOT + "/");
    }
}
