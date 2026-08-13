package com.richard.fyoung.customeradmin.common.storage;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 后台图片（菜单图标 / 登录轮播图）的存储读写：写走对象存储，读带一层<b>本地盘存量兜底</b>。
 *
 * <p>存储后端复用 starter 的 {@link AttachmentFileStorage} SPI（{@code local} / {@code minio} 由
 * {@code customer-work.attachment.storage.type} 决定），不再各自 {@code Files.write} 一套——
 * 多副本部署时 A 机上传的图 B 机读不到，正是这次要解决的问题。</p>
 *
 * <p><b>为什么保留本地盘兜底</b>：改造前的图片落在 {@code ./data/menu} / {@code ./data/login-images}，
 * DB 里存的 URL 形如 {@code /api/menu-icons/{uuid}.png}（key 无 {@code yyyyMM} 前缀）。
 * 读不到对象时回退到旧目录按 key 找一次，存量图片因此无需数据迁移即可继续访问；
 * 新上传的一律进对象存储，旧图随运维节奏自然淘汰。</p>
 * @author owlzhangfq@gmail.com
 */
public class ImageStorageSupport {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageSupport.class);

    private final AttachmentFileStorage fileStorage;
    private final String legacyRoot;

    /**
     * @param fileStorage 对象存储 SPI（新图的唯一写入口）
     * @param legacyRoot  改造前的本地盘目录，仅用于读兜底
     */
    public ImageStorageSupport(AttachmentFileStorage fileStorage, String legacyRoot) {
        this.fileStorage = fileStorage;
        this.legacyRoot = legacyRoot;
    }

    /** 写入图片字节，返回相对 key（形如 {@code 202608/{uuid}.png}）。 */
    public String store(byte[] data, String id, String ext) throws IOException {
        return fileStorage.store(data, id, ext);
    }

    /**
     * 按 key 读图片字节：先查对象存储，未命中再查旧本地盘目录。
     *
     * @throws IOException 两处都没有
     */
    public byte[] read(String key) throws IOException {
        try {
            return fileStorage.read(key);
        } catch (Exception e) {
            byte[] legacy = readLegacy(key);
            if (legacy != null) {
                return legacy;
            }
            throw new IOException("image not found in storage or legacy dir: " + key, e);
        }
    }

    /**
     * 按 key 删除图片：两处都删（存量图可能还在旧目录里），失败只记 error 不抛——
     * 删除记录的主链路在 DB 侧，残留文件不影响功能，可运维期清理。
     */
    public void delete(String key) {
        try {
            fileStorage.delete(key);
        } catch (Exception e) {
            log.error("delete image from storage failed, code={}, key={}", "ADMIN-IMAGE-DELETE-FAIL", key, e);
        }
        try {
            Path target = resolveLegacy(key);
            if (target != null) {
                Files.deleteIfExists(target);
            }
        } catch (Exception e) {
            log.error("delete legacy image file failed, code={}, key={}", "ADMIN-IMAGE-LEGACY-DELETE-FAIL", key, e);
        }
    }

    /** 旧本地盘读兜底；文件不存在或路径非法时返回 null（交调用方翻译成"找不到"）。 */
    private byte[] readLegacy(String key) {
        try {
            Path target = resolveLegacy(key);
            if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (Exception e) {
            log.error("read legacy image file failed, code={}, key={}", "ADMIN-IMAGE-LEGACY-READ-FAIL", key, e);
            return null;
        }
    }

    /**
     * 解析旧目录下的路径。key 来自 URL，故这里是本类的路径穿越防御点：
     * 规范化后必须仍落在 legacyRoot 之内，越界返回 null（不抛异常——读兜底失败只该表现为"没找到"）。
     */
    private Path resolveLegacy(String key) {
        Path base = Paths.get(legacyRoot).normalize().toAbsolutePath();
        Path target = base.resolve(key).normalize().toAbsolutePath();
        if (!target.startsWith(base)) {
            log.error("legacy image path escapes root, code={}, key={}", "ADMIN-IMAGE-PATH-TRAVERSAL", key);
            return null;
        }
        return target;
    }
}
