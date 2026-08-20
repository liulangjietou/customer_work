package com.richard.fyoung.customerwork.core.common.crypto;

import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM 加解密（JDK 内置 {@code javax.crypto}，不引入三方依赖，不依赖 Spring 容器）。
 *
 * <p>starter 与 admin 两侧敏感字段加密的<b>唯一实现</b>：admin 的
 * {@code AesGcmCryptoUtil}（@Component + @Value 注入密钥）与 starter 的
 * {@code AesGcmDecryptor}（Nacos 运行时配置消费端）都是本类的薄壳，密钥配置项各自独立
 * （{@code admin.aes-secret-key} 与 {@code customer-work.nacos.config-aes-key}），
 * 保持两侧配置解耦、只共享算法实现。</p>
 *
 * <p><b>密文格式不可变更</b>：Base64(IV[12B] + cipherText)，GCM tag 128 位。
 * 库中已有大量按此格式加密的存量密文（{@code ai_model_config.api_key}、
 * {@code sql_datasource.password}、渠道机器人凭据等），改格式会导致存量密文全部无法解密。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class AesGcmCrypto {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    /** GCM 推荐 IV 长度（字节）：RFC 5116 定的 96 位，改这个值会让历史密文全部解不开。 */
    public static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    /** 掩码保留的末尾明文位数。 */
    private static final int MASK_KEEP_LENGTH = 4;
    private static final String MASK_PLACEHOLDER = "****";

    private final SecretKeySpec keySpec;

    /**
     * @param secretKey        AES 密钥明文，长度必须为 16/24/32 字节（AES-128/192/256）
     * @param keyPropertyName  密钥来源的配置项名，仅用于启动期失败时定位问题
     */
    public AesGcmCrypto(String secretKey, String keyPropertyName) {
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException(keyPropertyName + " is blank, refuse to start (sensitive field encryption depends on it)");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                keyPropertyName + " length must be 16/24/32 bytes (AES-128/192/256), current=" + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密明文，返回 Base64(IV + 密文)；IV 每次随机，同一明文两次加密结果不同。 */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("aes gcm encrypt failed", e);
        }
    }

    /**
     * 解密 {@link #encrypt} 产出的 Base64 密文。
     *
     * @throws IllegalStateException 密文非法 / 密钥不匹配 / GCM tag 校验失败
     */
    public String decrypt(String cipherTextBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(cipherTextBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("aes gcm decrypt failed", e);
        }
    }

    /** 掩码显示：只保留末 4 位，其余替换为 *（用于列表/详情接口回显，不暴露明文）。 */
    public static String mask(String plainText) {
        if (plainText == null || plainText.length() <= MASK_KEEP_LENGTH) {
            return MASK_PLACEHOLDER;
        }
        return "*".repeat(plainText.length() - MASK_KEEP_LENGTH)
            + plainText.substring(plainText.length() - MASK_KEEP_LENGTH);
    }
}
