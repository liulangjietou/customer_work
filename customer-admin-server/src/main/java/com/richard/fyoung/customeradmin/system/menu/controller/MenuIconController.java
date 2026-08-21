package com.richard.fyoung.customeradmin.system.menu.controller;

import com.richard.fyoung.customeradmin.common.storage.ImageMediaTypes;
import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
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

    private static final String HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    private static final String HEADER_X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String IMAGE_SANDBOX_POLICY = "default-src 'none'; sandbox";

    private final MenuIconStorageService storageService;
    private final PermissionService permissionService;

    public MenuIconController(MenuIconStorageService storageService, PermissionService permissionService) {
        this.storageService = storageService;
        this.permissionService = permissionService;
    }

    /** key 可能带 {@code yyyyMM/} 前缀（含斜杠），故用 {@code /**} 通配；找不到即 404，不泄漏存储细节。 */
    @GetMapping("/**")
    public ResponseEntity<byte[]> icon(HttpServletRequest request) {
        String key = ImageMediaTypes.extractKey(request, MenuIconStorageService.URL_PREFIX);
        String imageUrl = MenuIconStorageService.URL_PREFIX + key;
        if (!storageService.ownsKey(key) && !permissionService.isReferencedImageUrl(imageUrl)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] data = storageService.read(key);
            return ResponseEntity.ok()
                .contentType(ImageMediaTypes.byExtension(key))
                // SVG 允许作为 <img> 图标，但直接打开时必须进入无脚本沙箱，避免同源读取后台 token。
                .header(HEADER_CONTENT_SECURITY_POLICY, IMAGE_SANDBOX_POLICY)
                .header(HEADER_X_CONTENT_TYPE_OPTIONS, "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(data);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
