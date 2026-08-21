package com.richard.fyoung.customeradmin.system.loginimage.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.storage.ImageMediaTypes;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginImageStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 登录页轮播图公开接口：图列表 + 图片本体，均在未登录状态下访问，路径挂在
 * {@code /api/login-images/**} 下共用同一条 Sa-Token 白名单。只暴露 URL 与图片字节，无敏感信息。
 *
 * <p>图片本体改由本 Controller 从对象存储读出（此前是 {@code StaticResourceConfig} 直接映射本地目录，
 * 多副本部署时 A 机上传的图 B 机读不到）。URL 契约不变，DB 里的存量地址继续有效。
 * {@code /list} 是更具体的映射，不会被 {@code /**} 遮蔽。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/login-images")
public class LoginImagePublicController {

    private final LoginCarouselImageService imageService;
    private final LoginImageStorageService storageService;

    public LoginImagePublicController(LoginCarouselImageService imageService,
                                      LoginImageStorageService storageService) {
        this.imageService = imageService;
        this.storageService = storageService;
    }

    @GetMapping("/list")
    public Result<List<String>> list() {
        return Result.success(imageService.listEnabledUrls());
    }

    /**
     * 出图：{@code /api/login-images/{key}}，key 可能带 {@code yyyyMM/} 前缀，故用 {@code /**}
     * 通配后从 {@link HandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} 取完整剩余路径
     * （{@code @PathVariable} 遇到斜杠会截断）。找不到即 404，不泄漏存储细节。
     */
    @GetMapping("/**")
    public ResponseEntity<byte[]> image(HttpServletRequest request) {
        String key = ImageMediaTypes.extractKey(request, LoginImageStorageService.URL_PREFIX);
        String imageUrl = LoginImageStorageService.URL_PREFIX + key;
        if (!storageService.ownsKey(key) && !imageService.isReferencedImageUrl(imageUrl)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] data = storageService.read(key);
            return ResponseEntity.ok()
                .contentType(ImageMediaTypes.byExtension(key))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(data);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
