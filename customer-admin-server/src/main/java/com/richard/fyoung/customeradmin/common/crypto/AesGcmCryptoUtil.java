package com.richard.fyoung.customeradmin.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES/GCM 加解密工具（JDK 内置 {@code javax.crypto}，不引入三方依赖）。
 *
 * <p>当前唯一使用场景：{@code ai_model_config.api_key} 字段加密存储（需求文档 3.1）。
 * IV 随机 12 字节，拼接在密文前缀一起 Base64 编码存库（单字段自包含，不需要额外一列存 IV）。
 * 密钥经 {@code admin.aes-secret-key} 环境变量注入，不入库不硬编码；仅一个敏感字段，不做成通用
 * 字段级加密注解框架（过度抽象不划算，见实施计划 3.5 节）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AesGcmCryptoUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;

    public AesGcmCryptoUtil(@Value("${admin.aes-secret-key}") String secretKey) {
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("admin.aes-secret-key 未配置，拒绝启动（AppKey 加密依赖该密钥）");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                "admin.aes-secret-key 长度必须为 16/24/32 字节（AES-128/192/256），当前=" + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密明文，返回 Base64（IV + 密文）。 */
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
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    /** 解密 {@link #encrypt} 产出的 Base64 密文。 */
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
            throw new IllegalStateException("AES 解密失败", e);
        }
    }

    /** 掩码显示：只保留末 4 位，其余替换为 *（用于列表/详情接口回显，不暴露明文）。 */
    public static String mask(String plainText) {
        if (plainText == null || plainText.length() <= 4) {
            return "****";
        }
        int keepLen = 4;
        return "*".repeat(plainText.length() - keepLen) + plainText.substring(plainText.length() - keepLen);
    }
}
