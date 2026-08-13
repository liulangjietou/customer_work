package com.richard.fyoung.customeradmin.system.menu.controller;

import com.richard.fyoung.customeradmin.common.storage.ImageMediaTypes;
import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;

/**
 * 菜单图标出图接口：{@code /api/menu-icons/{key}}，从对象存储读出图片字节。
 *
 * <p>此前由 {@code StaticResourceConfig} 直接映射本地目录 {@code ./data/menu}，多副本部署时
 * A 机上传的图 B 机读不到。改走 Controller 后 URL 契约不变（仍在 Sa-Token 白名单内、
 * 仍是 {@code /api} 前缀以复用前端 Vite 代理），{@code sys_menu.icon} 里的存量地址继续有效。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/menu-icons")
public class MenuIconController {

    private final MenuIconStorageService storageService;

    public MenuIconController(MenuIconStorageService storageService) {
        this.storageService = storageService;
    }

    /** key 可能带 {@code yyyyMM/} 前缀（含斜杠），故用 {@code /**} 通配；找不到即 404，不泄漏存储细节。 */
    @GetMapping("/**")
    public ResponseEntity<byte[]> icon(HttpServletRequest request) {
        String key = ImageMediaTypes.extractKey(request, MenuIconStorageService.URL_PREFIX);
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
