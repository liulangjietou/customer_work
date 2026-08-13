package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.common.storage.ImageStorageSupport;
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
 * <p>URL 契约与改造前完全一致（{@code /api/login-images/{key}}），故 {@code sys_login_carousel_image.image_url}
 * 里已存的地址无需迁移——读取时对象存储未命中会回落旧目录 {@link #LEGACY_IMAGE_ROOT}，见
 * {@link ImageStorageSupport}。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class LoginImageStorageService {

    /** 改造前的落盘目录，现仅用于读存量图片的兜底。 */
    public static final String LEGACY_IMAGE_ROOT = "./data/login-images";

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

    private final ImageStorageSupport storage;

    public LoginImageStorageService(AttachmentFileStorage fileStorage) {
        this.storage = new ImageStorageSupport(fileStorage, LEGACY_IMAGE_ROOT);
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
            return URL_PREFIX + storage.store(data, UUID.randomUUID().toString(), extension);
        } catch (IOException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "图片保存失败: " + e.getMessage());
        }
    }

    /**
     * 按相对 key 读图片字节（供 {@code LoginImagePublicController} 出图）。
     *
     * @throws IOException 对象存储与旧目录都没有该图
     */
    public byte[] read(String key) throws IOException {
        return storage.read(key);
    }

    /**
     * 按访问 URL 删除图片文件。删除记录的主链路在 DB 侧，文件清理失败只记 error 不中断
     * （残留文件不影响功能，可运维期清理，兜底在 {@link ImageStorageSupport#delete}）。
     */
    public void delete(String imageUrl) {
        if (!StringUtils.hasText(imageUrl) || !imageUrl.startsWith(URL_PREFIX)) {
            return;
        }
        storage.delete(imageUrl.substring(URL_PREFIX.length()));
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
