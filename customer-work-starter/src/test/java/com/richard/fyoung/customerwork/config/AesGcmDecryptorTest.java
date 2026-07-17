package com.richard.fyoung.customerwork.config;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AesGcmDecryptor} 双侧格式契约单测：用与 admin {@code AesGcmCryptoUtil} <b>逐字节一致</b>的加密
 * 算法产出密文，验证 starter 消费端能解密 admin 发布端加密的 API Key（跨模块密文互通）。
 * @author owlzhangfq@gmail.com
 */
class AesGcmDecryptorTest {

    /** 32 字节密钥（AES-256），两侧须同值（starter config-aes-key = admin ADMIN_AES_SECRET_KEY）。 */
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    /** 复刻 admin AesGcmCryptoUtil#encrypt：Base64(IV[12] + cipher)，GCM tag 128 位。 */
    private static String adminEncrypt(String plain, String key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
            new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    @Test
    void decryptsAdminCiphertext() throws Exception {
        String plain = "sk-secret-api-key-12345";
        String cipher = adminEncrypt(plain, KEY);
        AesGcmDecryptor decryptor = new AesGcmDecryptor(KEY);
        assertEquals(plain, decryptor.decrypt(cipher), "starter 应能解密 admin 加密的密文");
    }

    @Test
    void wrongKeyFailsToDecrypt() throws Exception {
        String cipher = adminEncrypt("sk-secret", KEY);
        AesGcmDecryptor decryptor = new AesGcmDecryptor("ffffffffffffffffffffffffffffffff");
        assertThrows(IllegalStateException.class, () -> decryptor.decrypt(cipher));
    }

    @Test
    void corruptedCiphertextFails() {
        AesGcmDecryptor decryptor = new AesGcmDecryptor(KEY);
        assertThrows(IllegalStateException.class, () -> decryptor.decrypt("not-a-valid-cipher"));
    }

    @Test
    void rejectsInvalidKeyLength() {
        assertThrows(IllegalStateException.class, () -> new AesGcmDecryptor("short"));
        assertThrows(IllegalStateException.class, () -> new AesGcmDecryptor(""));
    }
}
