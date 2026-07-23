package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginImageStorageService} 校验单测：空文件/超大/非法扩展名/低分辨率全部在落盘前
 * fast fail（只测拒绝路径，不写磁盘）。低分辨率用例用内存生成的真实 PNG 验证 ImageIO 解码链路。
 * @author owlzhangfq@gmail.com
 */
class LoginImageStorageServiceTest {

    private LoginImageStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LoginImageStorageService();
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void store_shouldFailWhenFileEmpty() {
        assertThrows(BizException.class, () -> storageService.store(
            new MockMultipartFile("file", "bg.png", "image/png", new byte[0])));
    }

    @Test
    void store_shouldFailWhenExtensionNotAllowed() {
        assertThrows(BizException.class, () -> storageService.store(
            new MockMultipartFile("file", "bg.svg", "image/svg+xml", new byte[] {1})));
    }

    @Test
    void store_shouldFailWhenOverSizeLimit() {
        assertThrows(BizException.class, () -> storageService.store(
            new MockMultipartFile("file", "bg.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1])));
    }

    @Test
    void store_shouldFailWhenResolutionTooLow() throws IOException {
        BizException exception = assertThrows(BizException.class, () -> storageService.store(
            new MockMultipartFile("file", "small.png", "image/png", pngBytes(452, 300))));
        assertTrue(exception.getMessage().contains("分辨率过低"));
    }
}
