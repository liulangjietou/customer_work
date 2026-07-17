package com.richard.fyoung.customerwork.config;

import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES/GCM 解密器（消费端，与 admin 的 {@code AesGcmCryptoUtil} 密文格式严格对齐）。
 *
 * <p>格式契约：Base64(IV[12B] + cipherText)，GCM tag 128 位。admin 用同一密钥加密 API Key 密文，
 * starter 拉到运行时配置后用本类解密还原明文注入模型链。<b>不依赖 admin 模块，独立实现</b>——两侧
 * 通过密钥（{@code customer-work.nacos.config-aes-key} 与 admin 的 {@code ADMIN_AES_SECRET_KEY} 同值）
 * 与本格式约定形成契约，各自演进互不编译耦合。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class AesGcmDecryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;

    public AesGcmDecryptor(String secretKey) {
        if (!StringUtils.hasText(secretKey)) {
            throw new IllegalStateException(
                "customer-work.nacos.config-aes-key is blank, cannot decrypt runtime config api key");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                "customer-work.nacos.config-aes-key length must be 16/24/32 bytes (AES-128/192/256), current="
                    + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 解密 admin 侧 {@code AesGcmCryptoUtil#encrypt} 产出的 Base64 密文。
     *
     * @param cipherTextBase64 Base64(IV + cipherText)
     * @return 明文
     * @throws IllegalStateException 密文非法 / 密钥不匹配 / tag 校验失败
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
            throw new IllegalStateException("runtime config api key decrypt failed", e);
        }
    }
}
