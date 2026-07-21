package com.richard.fyoung.customerwork.tool.devtool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodecDevToolOps} 单测：Base64/URL 编解码、hash/HMAC 已知向量、UUID 边界、
 * AES 三模式加解密回环、密钥长度与坏输入。
 * @author owlzhangfq@gmail.com
 */
class CodecDevToolOpsTest {

    private final CodecDevToolOps ops = new CodecDevToolOps();

    // -------- Base64 / URL --------

    @Test
    void base64_shouldRoundTrip() {
        String encoded = ops.base64Encode("你好 hello");
        assertEquals("你好 hello", ops.base64Decode(encoded));
    }

    @Test
    void base64Decode_shouldThrow_onIllegalInput() {
        assertThrows(IllegalArgumentException.class, () -> ops.base64Decode("!!!not-base64!!!"));
    }

    @Test
    void url_shouldRoundTrip() {
        String encoded = ops.urlEncode("a b&c=中文");
        assertEquals("a b&c=中文", ops.urlDecode(encoded));
    }

    // -------- Hash / HMAC --------

    @Test
    void hash_md5_shouldMatchKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ops.hash("MD5", "abc", null));
    }

    @Test
    void hash_sha256_shouldMatchKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ops.hash("SHA-256", "abc", null));
    }

    @Test
    void hash_hmacSha256_shouldMatchKnownVector() {
        // RFC 已知向量：HmacSHA256(key="key", "The quick brown fox jumps over the lazy dog")
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            ops.hash("SHA-256", "The quick brown fox jumps over the lazy dog", "key"));
    }

    @Test
    void hash_withHmacKey_shouldDifferFromPlainHash() {
        assertNotEquals(ops.hash("SHA-256", "abc", null), ops.hash("SHA-256", "abc", "secret"));
    }

    @Test
    void hash_shouldRejectUnsupportedAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> ops.hash("SHA-999", "abc", null));
    }

    // -------- UUID --------

    @Test
    void uuid_shouldGenerateRequestedCount() {
        List<String> list = ops.uuid(5);
        assertEquals(5, list.size());
        assertEquals(5, list.stream().distinct().count());
    }

    @Test
    void uuid_shouldRejectOutOfRangeCount() {
        assertThrows(IllegalArgumentException.class, () -> ops.uuid(0));
        assertThrows(IllegalArgumentException.class, () -> ops.uuid(21));
    }

    // -------- AES --------

    @Test
    void aes_cbc_shouldRoundTrip_withGeneratedIv() {
        String key = "1234567890123456"; // 16 字节
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("secret-明文", key, "CBC", null);
        assertNotNull(enc.getIv(), "CBC 应返回随机 IV");
        assertEquals("secret-明文", ops.aesDecrypt(enc.getCiphertext(), key, "CBC", enc.getIv()));
    }

    @Test
    void aes_cbc_shouldDefaultMode_whenModeBlank() {
        String key = "1234567890123456";
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("hello", key, null, null);
        assertEquals("CBC", enc.getMode());
        assertEquals("hello", ops.aesDecrypt(enc.getCiphertext(), key, null, enc.getIv()));
    }

    @Test
    void aes_ecb_shouldRoundTrip_withoutIv() {
        String key = "123456789012345678901234"; // 24 字节
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("ecb-明文", key, "ECB", null);
        assertNull(enc.getIv(), "ECB 不应有 IV");
        assertEquals("ecb-明文", ops.aesDecrypt(enc.getCiphertext(), key, "ECB", null));
    }

    @Test
    void aes_gcm_shouldRoundTrip() {
        String key = "12345678901234567890123456789012"; // 32 字节
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("gcm-明文", key, "GCM", null);
        assertNotNull(enc.getIv());
        assertEquals("gcm-明文", ops.aesDecrypt(enc.getCiphertext(), key, "GCM", enc.getIv()));
    }

    @Test
    void aes_shouldRejectIllegalKeyLength() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", "short-key", "CBC", null));
        assertTrue(ex.getMessage().contains("16/24/32"));
    }

    @Test
    void aesDecrypt_shouldRejectMissingIv_forCbc() {
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesDecrypt("YWJj", "1234567890123456", "CBC", null));
    }

    @Test
    void aes_shouldRejectIllegalMode() {
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", "1234567890123456", "XTS", null));
    }
}
