package com.richard.fyoung.customeradmin.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AES/GCM 加解密工具单测：往返一致性、每次加密 IV 随机不同、密钥长度校验、掩码显示。
 * @author owlzhangfq@gmail.com
 */
class AesGcmCryptoUtilTest {

    private static final String KEY_32 = "0123456789abcdef0123456789abcdef";

    @Test
    void encryptDecrypt_shouldRoundTrip() {
        AesGcmCryptoUtil util = new AesGcmCryptoUtil(KEY_32);
        String plain = "sk-test-api-key-1234567890";

        String cipherText = util.encrypt(plain);
        assertNotEquals(plain, cipherText);
        assertEquals(plain, util.decrypt(cipherText));
    }

    @Test
    void encrypt_shouldProduceDifferentCipherText_eachTime() {
        AesGcmCryptoUtil util = new AesGcmCryptoUtil(KEY_32);
        String plain = "same-plain-text";

        String c1 = util.encrypt(plain);
        String c2 = util.encrypt(plain);

        assertNotEquals(c1, c2, "IV 随机，同一明文两次加密结果应不同");
        assertEquals(plain, util.decrypt(c1));
        assertEquals(plain, util.decrypt(c2));
    }

    @Test
    void constructor_shouldRejectBlankKey() {
        assertThrows(IllegalStateException.class, () -> new AesGcmCryptoUtil(""));
        assertThrows(IllegalStateException.class, () -> new AesGcmCryptoUtil(null));
    }

    @Test
    void constructor_shouldRejectInvalidKeyLength() {
        assertThrows(IllegalStateException.class, () -> new AesGcmCryptoUtil("too-short"));
    }

    @Test
    void mask_shouldKeepOnlyLastFourChars() {
        String input = "sk-1234567890abcd";
        String masked = AesGcmCryptoUtil.mask(input);
        assertEquals(input.length(), masked.length(), "掩码长度应与原文一致");
        assertTrue(masked.endsWith("abcd"), "应保留末 4 位");
        assertTrue(masked.substring(0, masked.length() - 4).chars().allMatch(c -> c == '*'),
            "前面部分应全为掩码字符");

        assertEquals("****", AesGcmCryptoUtil.mask("ab"), "长度不足 4 位时统一返回 ****");
        assertEquals("****", AesGcmCryptoUtil.mask(null));
    }

    @Test
    void tamperedCipherText_shouldFailToDecrypt() {
        AesGcmCryptoUtil util = new AesGcmCryptoUtil(KEY_32);
        String cipherText = util.encrypt("plain");
        String tampered = cipherText.substring(0, cipherText.length() - 4) + "abcd";

        assertThrows(IllegalStateException.class, () -> util.decrypt(tampered));
    }
}
