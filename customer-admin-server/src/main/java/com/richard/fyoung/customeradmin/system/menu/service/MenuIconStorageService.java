package com.richard.fyoung.customeradmin.system.menu.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 菜单图标图片上传：写入对象存储（starter 的 {@link AttachmentFileStorage} SPI，MinIO），通过
 * {@link com.richard.fyoung.customeradmin.system.menu.controller.MenuIconController}
 * 映射的 {@code /api/menu-icons/**} 对外提供访问 URL。
 *
 * <p>URL 契约与改造前一致（{@code /api/menu-icons/{key}}），但<b>不再有本地盘</b>：
 * 项目内不落任何文件，{@code sys_menu.icon} 里改造前写入的地址（对应旧 {@code ./data/menu} 下的文件）
 * 需要重新上传图标，或由运维把旧文件按同名 key 灌进 MinIO。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class MenuIconStorageService {

    /** 与 {@code MenuIconController} 的映射保持一致，改这里要同步改那边。 */
    public static final String URL_PREFIX = "/api/menu-icons/";

    private static final String STORAGE_ID_PREFIX = "menu-icon-";
    private static final long MAX_UPLOAD_BYTES = 1024 * 1024; // 1MB，图标没必要更大
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "svg");
    private static final Pattern OWNED_KEY_PATTERN = Pattern.compile(
        "^\\d{6}/" + STORAGE_ID_PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            + "[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|jpeg|gif|svg)$");

    private final AttachmentFileStorage fileStorage;

    public MenuIconStorageService(AttachmentFileStorage fileStorage) {
        this.fileStorage = fileStorage;
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
            String storageId = STORAGE_ID_PREFIX + UUID.randomUUID();
            return URL_PREFIX + fileStorage.store(file.getBytes(), storageId, extension);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图标图片保存失败: " + e.getMessage());
        }
    }

    /** 新上传图标使用独立命名空间，公开出图接口据此与同桶中的私有附件隔离。 */
    public boolean ownsKey(String key) {
        return StringUtils.hasText(key) && OWNED_KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 按相对 key 读图标字节（供 {@code MenuIconController} 出图）。
     *
     * @throws IOException 对象不存在或读取失败
     */
    public byte[] read(String key) throws IOException {
        return fileStorage.read(key);
    }

    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件名缺少扩展名");
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
