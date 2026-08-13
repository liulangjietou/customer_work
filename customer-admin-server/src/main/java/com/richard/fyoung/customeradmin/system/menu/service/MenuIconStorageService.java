package com.richard.fyoung.customeradmin.system.menu.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.common.storage.ImageStorageSupport;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 菜单图标图片上传：写入对象存储（starter 的 {@link AttachmentFileStorage} SPI，
 * {@code customer-work.attachment.storage.type=minio} 时即 MinIO），通过
 * {@link com.richard.fyoung.customeradmin.system.menu.controller.MenuIconController}
 * 映射的 {@code /api/menu-icons/**} 对外提供访问 URL。
 *
 * <p>URL 契约与改造前完全一致（{@code /api/menu-icons/{key}}），故 {@code sys_menu.icon} 里
 * 已存的地址无需迁移——读取时对象存储未命中会回落旧目录 {@link #LEGACY_ICON_ROOT}，见
 * {@link ImageStorageSupport}。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class MenuIconStorageService {

    /** 改造前的落盘目录，现仅用于读存量图片的兜底。 */
    public static final String LEGACY_ICON_ROOT = "./data/menu";

    /** 与 {@code MenuIconController} 的映射保持一致，改这里要同步改那边。 */
    public static final String URL_PREFIX = "/api/menu-icons/";

    private static final long MAX_UPLOAD_BYTES = 1024 * 1024; // 1MB，图标没必要更大
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "svg");

    private final ImageStorageSupport storage;

    public MenuIconStorageService(AttachmentFileStorage fileStorage) {
        this.storage = new ImageStorageSupport(fileStorage, LEGACY_ICON_ROOT);
    }

    /** @return 可访问 URL（相对路径，前端拼自身 origin 即可直接 <img> 展示）。 */
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
        try {
            return URL_PREFIX + storage.store(file.getBytes(), UUID.randomUUID().toString(), extension);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图标图片保存失败: " + e.getMessage());
        }
    }

    /**
     * 按相对 key 读图标字节（供 {@code MenuIconController} 出图）。
     *
     * @throws IOException 对象存储与旧目录都没有该图
     */
    public byte[] read(String key) throws IOException {
        return storage.read(key);
    }

    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件名缺少扩展名");
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
