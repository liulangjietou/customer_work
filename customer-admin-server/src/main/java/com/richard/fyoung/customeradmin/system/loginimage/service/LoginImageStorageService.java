package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 登录页轮播图文件存储：落盘到本地磁盘 {@code ./data/login-images}（沿用
 * {@link com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService}
 * 同一套本地磁盘约定），通过 {@link com.richard.fyoung.customeradmin.config.StaticResourceConfig}
 * 映射的 {@code /api/login-images/**} 对外提供访问 URL。
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class LoginImageStorageService {

    /** 与 {@code StaticResourceConfig} 里的资源映射保持一致，改这里要同步改那边。 */
    public static final String IMAGE_ROOT = "./data/login-images";
    private static final String URL_PREFIX = "/api/login-images/";

    /** 登录页背景是全屏大图，比菜单图标放宽到 5MB。 */
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    /**
     * 最低分辨率门槛：背景图以 cover 铺满整屏，低于主流视口（1280×720，高分屏还要再乘 DPR）
     * 会被放大导致模糊，上传时直接 fast fail，比事后在登录页发虚好排查得多。
     */
    private static final int MIN_WIDTH = 1280;
    private static final int MIN_HEIGHT = 720;

    /** @return 落盘后的可访问 URL（相对路径，前端拼自身 origin 即可直接展示）。 */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片大小超过 5MB 限制");
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅支持 png/jpg/jpeg/webp 格式图片");
        }
        checkMinResolution(file);

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Paths.get(IMAGE_ROOT);
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片保存失败: " + e.getMessage());
        }
        return URL_PREFIX + filename;
    }

    /**
     * 按访问 URL 删除磁盘文件。删除记录的主链路在 DB 侧，文件清理失败只记 error 不中断
     * （残留文件不影响功能，可运维期清理）。
     */
    public void delete(String imageUrl) {
        if (!StringUtils.hasText(imageUrl) || !imageUrl.startsWith(URL_PREFIX)) {
            return;
        }
        String filename = imageUrl.substring(URL_PREFIX.length());
        // URL 由本服务生成（uuid.ext），这里防一手路径穿越，杜绝删到目录外的文件
        if (filename.contains("/") || filename.contains("..")) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(IMAGE_ROOT).resolve(filename));
        } catch (IOException e) {
            log.error("delete login image file failed, code={}, url={}", "LOGIN-IMAGE-FILE-DELETE-FAIL", imageUrl, e);
        }
    }

    /**
     * 校验图片实际像素不低于 {@link #MIN_WIDTH}×{@link #MIN_HEIGHT}。JDK 自带 ImageIO
     * 不认 webp（decode 返回 null），解不出来的按"无法校验"放行，不误杀合法 webp；
     * 读流失败按无效图片 fast fail。
     */
    private void checkMinResolution(MultipartFile file) {
        BufferedImage image;
        try {
            image = ImageIO.read(file.getInputStream());
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片读取失败: " + e.getMessage());
        }
        if (image == null) {
            return;
        }
        if (image.getWidth() < MIN_WIDTH || image.getHeight() < MIN_HEIGHT) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "图片分辨率过低（" + image.getWidth() + "×" + image.getHeight() + "），"
                    + "背景图会铺满整屏，低于 " + MIN_WIDTH + "×" + MIN_HEIGHT + " 会被拉伸模糊，请换高清图");
        }
    }

    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件名缺少扩展名");
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
