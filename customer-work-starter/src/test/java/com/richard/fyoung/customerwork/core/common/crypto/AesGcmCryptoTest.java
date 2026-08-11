package com.richard.fyoung.customerwork.core.common.crypto;

import com.richard.fyoung.customerwork.infra.config.AesGcmDecryptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AesGcmCrypto} 单测：往返一致、IV 随机、密钥长度校验、掩码，以及<b>密文格式兼容性</b>。
 *
 * <p>格式兼容用固定密钥 + 固定密文（golden vector）钉死：库中存量密文（模型 API Key、数据源密码等）
 * 都是这个格式，任何改动只要让 {@link #decryptsLegacyGoldenCipherText()} 变红就说明存量密文会解不开。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AesGcmCryptoTest {

    private static final String KEY_32 = "0123456789abcdef0123456789abcdef";
    private static final String KEY_PROPERTY = "test.aes-secret-key";

    /** 存量密文样本：KEY_32 加密 GOLDEN_PLAIN 的产物，Base64(IV[12] + cipher)，GCM tag 128 位。 */
    private static final String GOLDEN_CIPHER =
        "6V61j+SIHK3/heekRFm0mp5LodYpIepKT8kmIzYKCNBAq8of6AWOECZJTk02esf/lw==";
    private static final String GOLDEN_PLAIN = "sk-golden-vector-2026";

    private AesGcmCrypto crypto() {
        return new AesGcmCrypto(KEY_32, KEY_PROPERTY);
    }

    @Test
    void encryptDecrypt_shouldRoundTrip() {
        AesGcmCrypto crypto = crypto();
        String plain = "sk-test-api-key-1234567890";

        String cipherText = crypto.encrypt(plain);
        assertNotEquals(plain, cipherText);
        assertEquals(plain, crypto.decrypt(cipherText));
    }

    @Test
    void encrypt_shouldProduceDifferentCipherText_eachTime() {
        AesGcmCrypto crypto = crypto();
        String c1 = crypto.encrypt("same-plain-text");
        String c2 = crypto.encrypt("same-plain-text");

        assertNotEquals(c1, c2, "IV 随机，同一明文两次加密结果应不同");
        assertEquals("same-plain-text", crypto.decrypt(c1));
        assertEquals("same-plain-text", crypto.decrypt(c2));
    }

    @Test
    void decryptsLegacyGoldenCipherText() {
        assertEquals(GOLDEN_PLAIN, crypto().decrypt(GOLDEN_CIPHER), "密文格式变更会导致存量密文解不开");
    }

    @Test
    void aesGcmDecryptor_shouldMatchCrypto_onSameCipherText() {
        AesGcmCrypto crypto = crypto();
        AesGcmDecryptor decryptor = new AesGcmDecryptor(KEY_32);

        assertEquals(crypto.decrypt(GOLDEN_CIPHER), decryptor.decrypt(GOLDEN_CIPHER));
        assertEquals(GOLDEN_PLAIN, decryptor.decrypt(crypto.encrypt(GOLDEN_PLAIN)),
            "薄壳 AesGcmDecryptor 与 AesGcmCrypto 必须互通");
    }

    @Test
    void wrongKey_shouldFailToDecrypt() {
        AesGcmCrypto other = new AesGcmCrypto("ffffffffffffffffffffffffffffffff", KEY_PROPERTY);
        assertThrows(IllegalStateException.class, () -> other.decrypt(GOLDEN_CIPHER));
    }

    @Test
    void corruptedCipherText_shouldFailToDecrypt() {
        AesGcmCrypto crypto = crypto();
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("not-a-valid-cipher"));

        String tampered = GOLDEN_CIPHER.substring(0, GOLDEN_CIPHER.length() - 6) + "abcd==";
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void constructor_shouldRejectBlankOrInvalidLengthKey() {
        assertThrows(IllegalStateException.class, () -> new AesGcmCrypto("", KEY_PROPERTY));
        assertThrows(IllegalStateException.class, () -> new AesGcmCrypto(null, KEY_PROPERTY));
        assertThrows(IllegalStateException.class, () -> new AesGcmCrypto("too-short", KEY_PROPERTY));
    }

    @Test
    void mask_shouldKeepOnlyLastFourChars() {
        String input = "sk-1234567890abcd";
        String masked = AesGcmCrypto.mask(input);

        assertEquals(input.length(), masked.length(), "掩码长度应与原文一致");
        assertTrue(masked.endsWith("abcd"), "应保留末 4 位");
        assertTrue(masked.substring(0, masked.length() - 4).chars().allMatch(c -> c == '*'),
            "前面部分应全为掩码字符");

        assertEquals("****", AesGcmCrypto.mask("ab"), "长度不足 4 位时统一返回 ****");
        assertEquals("****", AesGcmCrypto.mask("abcd"), "恰好 4 位也不暴露明文");
        assertEquals("****", AesGcmCrypto.mask(null));
    }
}
