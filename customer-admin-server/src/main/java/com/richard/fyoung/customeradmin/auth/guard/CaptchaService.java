package com.richard.fyoung.customeradmin.auth.guard;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 图形验证码：自绘 PNG，不引入第三方验证码库。
 *
 * <p>字符集刻意剔除了 {@code 0 O o 1 I l 2 Z 5 S}——这些字形在小尺寸位图里人眼分不开，
 * 留着只会让真人反复失败而对脚本毫无影响。</p>
 *
 * <p><b>这道防线拦的是什么</b>：它挡不住定向的打码平台，拦的是"拿注册接口批量灌号"
 * 这类无人值守的脚本。真正的成本闸门是 IP 限流与人工审核，验证码只是把最廉价的那一类挡在门外。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public class CaptchaService {

    /** 剔除易混字形后的字符集。 */
    private static final char[] ALPHABET = "34679ACDEFGHJKLMNPQRTUVWXY".toCharArray();

    private static final String IMAGE_FORMAT = "png";
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private final CaptchaStore store;
    private final RegistrationGuardProperties.Captcha config;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(CaptchaStore store, RegistrationGuardProperties.Captcha config) {
        this.store = store;
        this.config = config;
    }

    /** 生成一张验证码，返回随图下发的校验凭据与 data URI 图片。 */
    public CaptchaChallenge issue() {
        String answer = randomText(config.getLength());
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        store.save(captchaId, answer.toLowerCase(Locale.ROOT), config.getTtlSeconds());
        return new CaptchaChallenge(captchaId, DATA_URI_PREFIX + render(answer), config.getTtlSeconds());
    }

    /**
     * 校验并消费一次验证码。
     *
     * <p>大小写不敏感：区分大小写只会显著提高真人的失败率，对脚本没有任何额外成本。</p>
     */
    public boolean verify(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String expected = store.consume(captchaId.trim());
        return expected != null && expected.equals(input.trim().toLowerCase(Locale.ROOT));
    }

    private String randomText(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 画图：渐变底 + 干扰线 + 逐字旋转，输出 Base64 PNG。 */
    private String render(String text) {
        int width = config.getWidth();
        int height = config.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(240, 243, 247));
            g.fillRect(0, 0, width, height);

            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(random.nextInt(120) + 100, random.nextInt(120) + 100,
                    random.nextInt(120) + 100));
                g.drawLine(random.nextInt(width), random.nextInt(height),
                    random.nextInt(width), random.nextInt(height));
            }

            int fontSize = (int) (height * 0.68);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            int step = width / (text.length() + 1);
            for (int i = 0; i < text.length(); i++) {
                AffineTransform origin = g.getTransform();
                double angle = (random.nextDouble() - 0.5) * 0.6;
                int x = step * (i + 1) - fontSize / 3;
                int y = height / 2 + fontSize / 3;
                g.rotate(angle, x, y);
                g.setColor(new Color(random.nextInt(90), random.nextInt(90), random.nextInt(120) + 40));
                g.drawString(String.valueOf(text.charAt(i)), x, y);
                g.setTransform(origin);
            }
        } finally {
            g.dispose();
        }
        return encode(image);
    }

    private String encode(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, IMAGE_FORMAT, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            // 写的是内存流，走到这里说明 JVM 图像栈异常，属于不可恢复的环境问题
            throw new UncheckedIOException(e);
        }
    }
}
