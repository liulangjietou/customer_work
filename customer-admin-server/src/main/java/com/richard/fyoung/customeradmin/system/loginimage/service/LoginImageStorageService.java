package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 登录页轮播图文件存储：写入对象存储（starter 的 {@link AttachmentFileStorage} SPI，
 * {@code customer-work.attachment.storage.type=minio} 时即 MinIO），通过
 * {@link com.richard.fyoung.customeradmin.system.loginimage.controller.LoginImagePublicController}
 * 映射的 {@code /api/login-images/**} 对外提供访问 URL。
 *
 * <p>URL 契约与改造前一致（{@code /api/login-images/{key}}），但<b>不再有本地盘</b>：
 * 项目内不落任何文件，{@code sys_login_carousel_image.image_url} 里改造前写入的地址
 * （对应旧 {@code ./data/login-images} 下的文件）需要重新上传，或由运维把旧文件按同名 key 灌进 MinIO。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class LoginImageStorageService {

    /** 与 {@code LoginImagePublicController} 的映射保持一致，改这里要同步改那边。 */
    public static final String URL_PREFIX = "/api/login-images/";

    /** 登录页背景是全屏大图，比菜单图标放宽到 5MB。 */
    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");

    /**
     * 最低分辨率门槛：背景图以 cover 铺满整屏，低于主流视口（1280×720，高分屏还要再乘 DPR）
     * 会被放大导致模糊，上传时直接 fast fail，比事后在登录页发虚好排查得多。
     */
    private static final int MIN_WIDTH = 1280;
    private static final int MIN_HEIGHT = 720;

    private final AttachmentFileStorage fileStorage;

    public LoginImageStorageService(AttachmentFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /** @return 可访问 URL（相对路径，前端拼自身 origin 即可直接展示）。 */
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
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片读取失败: " + e.getMessage());
        }
        checkMinResolution(data);
        try {
            return URL_PREFIX + fileStorage.store(data, UUID.randomUUID().toString(), extension);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片保存失败: " + e.getMessage());
        }
    }

    /**
     * 按相对 key 读图片字节（供 {@code LoginImagePublicController} 出图）。
     *
     * @throws IOException 对象不存在或读取失败
     */
    public byte[] read(String key) throws IOException {
        return fileStorage.read(key);
    }

    /**
     * 按访问 URL 删除图片对象。删除记录的主链路在 DB 侧，对象清理失败只记 error 不中断
     * （残留对象不影响功能，可运维期清理）。
     */
    public void delete(String imageUrl) {
        if (!StringUtils.hasText(imageUrl) || !imageUrl.startsWith(URL_PREFIX)) {
            return;
        }
        String key = imageUrl.substring(URL_PREFIX.length());
        try {
            fileStorage.delete(key);
        } catch (Exception e) {
            log.error("delete login image object failed, code={}, key={}", "LOGIN-IMAGE-DELETE-FAIL", key, e);
        }
    }

    /**
     * 校验图片实际像素不低于 {@link #MIN_WIDTH}×{@link #MIN_HEIGHT}。JDK 自带 ImageIO
     * 不认 webp（decode 返回 null），解不出来的按"无法校验"放行，不误杀合法 webp；
     * 读流失败按无效图片 fast fail。
     */
    private void checkMinResolution(byte[] data) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
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
