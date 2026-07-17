package com.richard.fyoung.customerwork.attachment;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PaddleOCR serving 集成测试（门控）：对接真实自建 serving（默认 {@code http://localhost:8868}，
 * 由 {@code docker/paddleocr/docker-compose.yml} 拉起）。服务不可达时自动跳过（照
 * {@link MybatisAttachmentStoreTest} 的可达性探测模式），可达时用运行时生成的含文字 PNG 走一次完整 recognize。
 *
 * <p>可用环境变量 {@code PADDLEOCR_BASE_URL} 覆盖地址。</p>
 * @author owlzhangfq@gmail.com
 */
class PaddleOcrVisionOcrIntegrationTest {

    private static String baseUrl() {
        String env = System.getenv("PADDLEOCR_BASE_URL");
        return (env == null || env.isBlank()) ? "http://localhost:8868" : env.trim();
    }

    private static boolean reachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 1500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 运行时用 Graphics2D 画一张含英文与数字的白底黑字 PNG，避免提交二进制测试资源。 */
    private static byte[] renderTextImage() throws Exception {
        BufferedImage img = new BufferedImage(480, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 480, 120);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString("Hello 12345", 30, 75);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void recognize_shouldReturnTextFromRealServing() throws Exception {
        String baseUrl = baseUrl();
        assumeTrue(reachable(baseUrl), "PaddleOCR serving 不可达（" + baseUrl + "），跳过该集成测试");

        // 首次请求 serving 会加载模型，给足超时（分钟级）
        PaddleOcrVisionOcrService service = new PaddleOcrVisionOcrService(baseUrl, "/ocr", 180);
        String text = service.recognize(renderTextImage(), "image/png");

        assertTrue(text != null && !text.isBlank(), "真实 serving 应识别出非空文本，实际=" + text);
    }
}
