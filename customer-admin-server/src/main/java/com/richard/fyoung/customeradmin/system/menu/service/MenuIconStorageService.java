package com.richard.fyoung.customeradmin.system.menu.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 菜单图标图片上传：落盘到本地磁盘 {@code ./data/menu}（项目里没有 OSS/MinIO，沿用
 * {@code ./data/admin-workspace} 同一套本地磁盘约定，目录不存在时自动创建），通过
 * {@link com.richard.fyoung.customeradmin.config.StaticResourceConfig}
 * 映射的 {@code /api/menu-icons/**} 对外提供访问 URL。
 * @author owlzhangfq@gmail.com
 */
@Service
public class MenuIconStorageService {

    /** 与 {@code StaticResourceConfig} 里的资源映射保持一致，改这里要同步改那边。 */
    public static final String ICON_ROOT = "./data/menu";
    private static final String URL_PREFIX = "/api/menu-icons/";

    private static final long MAX_UPLOAD_BYTES = 1024 * 1024; // 1MB，图标没必要更大
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "svg");

    /** @return 落盘后的可访问 URL（相对路径，前端拼自身 origin 即可直接 <img> 展示）。 */
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请选择要上传的图标图片");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "图标图片大小超过 1MB 限制");
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BizException(ResultCode.PARAM_INVALID, "仅支持 png/jpg/jpeg/gif/svg 格式图标");
        }

        String filename = UUID.randomUUID() + "." + extension;
        try {
            Path dir = Paths.get(ICON_ROOT);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            file.transferTo(target);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图标图片保存失败: " + e.getMessage());
        }
        return URL_PREFIX + filename;
    }

    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件名缺少扩展名");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return ext;
    }
}
