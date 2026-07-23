package com.richard.fyoung.customeradmin.system.loginimage.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录页轮播图公开读取接口：登录页在未登录状态下实时拉取启用图列表，路径挂在
 * {@code /api/login-images/**} 下与静态图片共用同一条 Sa-Token 白名单
 * （Controller 映射优先级高于静态资源映射，{@code /list} 不会被文件目录遮蔽；
 * 图片文件名是 uuid 也不会反向撞上 {@code list}）。只暴露 URL 列表，无敏感信息。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/login-images")
public class LoginImagePublicController {

    private final LoginCarouselImageService imageService;

    public LoginImagePublicController(LoginCarouselImageService imageService) {
        this.imageService = imageService;
    }

    @GetMapping("/list")
    public Result<List<String>> list() {
        return Result.success(imageService.listEnabledUrls());
    }
}
