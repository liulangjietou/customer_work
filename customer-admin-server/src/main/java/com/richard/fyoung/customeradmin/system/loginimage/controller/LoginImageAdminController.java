package com.richard.fyoung.customeradmin.system.loginimage.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginCarouselImageVO;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageEnabledRequest;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageReorderRequest;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 登录页轮播图管理（系统管理 › 登录页图片）：上传/列表/启停/排序/删除。
 * 登录页读取侧走 {@link LoginImagePublicController} 免鉴权接口。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/system/login-image")
public class LoginImageAdminController {

    private final LoginCarouselImageService imageService;

    public LoginImageAdminController(LoginCarouselImageService imageService) {
        this.imageService = imageService;
    }

    @SaCheckPermission("login-image:view")
    @GetMapping
    public Result<List<LoginCarouselImageVO>> list() {
        return Result.success(imageService.list());
    }

    @SaCheckPermission("login-image:add")
    @OperationLog(operation = "上传登录页轮播图", target = "login_carousel_image")
    @PostMapping
    public Result<LoginCarouselImageVO> upload(@RequestPart("file") MultipartFile file) {
        return Result.success(imageService.upload(file));
    }

    @SaCheckPermission("login-image:edit")
    @OperationLog(operation = "启停登录页轮播图", target = "login_carousel_image")
    @PutMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable Long id, @Valid @RequestBody LoginImageEnabledRequest request) {
        imageService.updateEnabled(id, request.enabled());
        return Result.success();
    }

    @SaCheckPermission("login-image:edit")
    @OperationLog(operation = "调整登录页轮播图顺序", target = "login_carousel_image")
    @PutMapping("/reorder")
    public Result<Void> reorder(@Valid @RequestBody LoginImageReorderRequest request) {
        imageService.reorder(request);
        return Result.success();
    }

    @SaCheckPermission("login-image:delete")
    @OperationLog(operation = "删除登录页轮播图", target = "login_carousel_image")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        imageService.delete(id);
        return Result.success();
    }
}
