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

    // -------- Hex --------

    @Test
    void hex_shouldRoundTrip() {
        assertEquals("e4bda0e5a5bd68690a", ops.hexEncode("你好hi\n"));
        assertEquals("你好hi\n", ops.hexDecode("e4bda0e5a5bd68690a"));
    }

    @Test
    void hexDecode_shouldIgnoreWhitespaceAndAcceptUpperCase() {
        assertEquals("abc", ops.hexDecode("61 62\n63"));
        assertEquals("abc", ops.hexDecode("616263".toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void hexDecode_shouldRejectOddLengthAndNonHex() {
        assertThrows(IllegalArgumentException.class, () -> ops.hexDecode("abc"));
        assertThrows(IllegalArgumentException.class, () -> ops.hexDecode("zz"));
    }

    // -------- AES：回环与参数校验 --------

    /** 只给密钥、其余全默认，验证默认值仍是历史行为（CBC + PKCS7 + IV/密文 base64）。 */
    @Test
    void aes_shouldKeepLegacyDefaults_whenOnlyKeyGiven() {
        CodecDevToolOps.AesParams params = params(b -> b.key("1234567890123456"));
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("hello", params);
        assertEquals("CBC", enc.getMode());
        assertEquals("PKCS7", enc.getPadding());
        assertEquals("base64", enc.getOutputFormat());
        assertNotNull(enc.getIv(), "CBC 应返回随机 IV");
        assertEquals("hello", ops.aesDecrypt(enc.getCiphertext(),
            params(b -> b.key("1234567890123456").iv(enc.getIv()))));
    }

    @Test
    void aes_ecb_shouldRoundTrip_withoutIv() {
        CodecDevToolOps.AesParams params = params(b -> b.key("123456789012345678901234").mode("ECB"));
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("ecb-明文", params);
        assertNull(enc.getIv(), "ECB 不应有 IV");
        assertEquals("ecb-明文", ops.aesDecrypt(enc.getCiphertext(), params));
    }

    @Test
    void aes_gcm_shouldRoundTrip() {
        CodecDevToolOps.AesParams params = params(b -> b.key("12345678901234567890123456789012").mode("GCM"));
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("gcm-明文", params);
        assertNotNull(enc.getIv());
        assertEquals("gcm-明文", ops.aesDecrypt(enc.getCiphertext(),
            params(b -> b.key("12345678901234567890123456789012").mode("GCM").iv(enc.getIv()))));
    }

    @Test
    void aes_ctr_shouldRoundTrip_withHexEncodings() {
        CodecDevToolOps.AesParams params = params(b -> b
            .key("000102030405060708090a0b0c0d0e0f").keyEncoding("hex")
            .mode("CTR").iv("0f0e0d0c0b0a09080706050403020100").ivEncoding("hex").outputFormat("hex"));
        CodecDevToolOps.AesResult enc = ops.aesEncrypt("ctr-明文", params);
        assertEquals("NONE", enc.getPadding(), "CTR 不使用块填充");
        assertEquals("ctr-明文", ops.aesDecrypt(enc.getCiphertext(), params));
    }

    @Test
    void aes_shouldRejectIllegalKeyLength() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", params(b -> b.key("short-key"))));
        assertTrue(ex.getMessage().contains("16/24/32"));
    }

    @Test
    void aesDecrypt_shouldRejectMissingIv_forCbc() {
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesDecrypt("YWJj", params(b -> b.key("1234567890123456"))));
    }

    @Test
    void aes_shouldRejectIllegalMode() {
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", params(b -> b.key("1234567890123456").mode("XTS"))));
    }

    /** CTR/GCM 显式指定块填充属于配置错误，必须报错而不是静默忽略。 */
    @Test
    void aes_shouldRejectBlockPadding_forStreamModes() {
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", params(b -> b.key("1234567890123456").mode("CTR").padding("PKCS7"))));
        assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("x", params(b -> b.key("1234567890123456").mode("GCM").padding("PKCS7"))));
    }

    /** NoPadding 下明文长度不是块长整数倍时，给出明确提示而非底层的 IllegalBlockSizeException。 */
    @Test
    void aes_noPadding_shouldRejectUnalignedPlainText() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ops.aesEncrypt("not-16-bytes", params(b -> b
                .key("1234567890123456").mode("CBC").padding("NONE").iv("abcdef9876543210").ivEncoding("utf8"))));
        assertTrue(ex.getMessage().contains("整数倍"));
    }

    // -------- AES：与管理台页面版(crypto-js)的互通性 --------

    /**
     * 下列密文全部由页面版实际使用的 crypto-js 生成（脚本逻辑逐行对应 aesCrypto.ts），
     * 断言后端能原样解出、且相同参数下加密结果逐字节一致。这是"页面加密的密文智能体一定能解"
     * 这条承诺的回归防线：任何一侧参数语义漂移都会让这些用例先挂。
     */
    @Test
    void aes_shouldInteropWithBrowserCryptoJs_cbcPkcs7Utf8IvHexOutput() {
        // 页面上的默认组合：CBC + PKCS7 + 密钥/IV 按 UTF-8 输入 + 密文 hex
        assertInterop("你好 hello 123", "7245506b0a54591da06e2d4e4f03721956ee0d78037759a433af7d0f0055c2ca",
            b -> b.key("0123456789abcdef").mode("CBC").iv("abcdef9876543210").ivEncoding("utf8").outputFormat("hex"));
    }

    @Test
    void aes_shouldInteropWithBrowserCryptoJs_cbcPkcs7Base64IvBase64Output() {
        assertInterop("你好 hello 123", "ckVQawpUWR2gbi1OTwNyGVbuDXgDd1mkM699DwBVwso=",
            b -> b.key("0123456789abcdef").mode("CBC").iv("YWJjZGVmOTg3NjU0MzIxMA==").ivEncoding("base64"));
    }

    @Test
    void aes_shouldInteropWithBrowserCryptoJs_ecb() {
        assertInterop("plain-ecb-测试", "37ee00d8416737fdcbde64ef7eb274e5377222e061a924c591cd9c27ea163ed4",
            b -> b.key("0123456789abcdef").mode("ECB").outputFormat("hex"));
    }

    @Test
    void aes_shouldInteropWithBrowserCryptoJs_ctr() {
        assertInterop("counter mode 中文", "43c68cfcc02929c8697098b94c4a21c7a13023",
            b -> b.key("000102030405060708090a0b0c0d0e0f").keyEncoding("hex").mode("CTR")
                .iv("0f0e0d0c0b0a09080706050403020100").ivEncoding("hex").outputFormat("hex"));
    }

    @Test
    void aes_shouldInteropWithBrowserCryptoJs_cbcNoPadding() {
        assertInterop("sixteen-bytes!!!", "7F42+jctB0RlgVrIBBHxIA==",
            b -> b.key("0123456789abcdef").mode("CBC").padding("NONE")
                .iv("abcdef9876543210").ivEncoding("utf8").outputFormat("base64"));
    }

    @Test
    void aes_shouldInteropWithBrowserCryptoJs_base64Key256() {
        assertInterop("aes-256 key from base64", "950a4ccdb215265262ad6b085e2d38b038a8ef42a032a545309abda8a37df883",
            b -> b.key("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=").keyEncoding("base64").mode("CBC")
                .iv("000102030405060708090a0b0c0d0e0f").ivEncoding("hex").outputFormat("hex"));
    }

    /** 双向断言：后端加密结果与浏览器密文逐字节相同，且后端能解开浏览器密文。 */
    private void assertInterop(String plainText, String browserCipher,
                               java.util.function.UnaryOperator<CodecDevToolOps.AesParams.AesParamsBuilder> customizer) {
        CodecDevToolOps.AesParams params = params(customizer);
        assertEquals(browserCipher, ops.aesEncrypt(plainText, params).getCiphertext(),
            "后端加密结果应与浏览器 crypto-js 逐字节一致");
        assertEquals(plainText, ops.aesDecrypt(browserCipher, params),
            "后端应能解开浏览器 crypto-js 产出的密文");
    }

    private CodecDevToolOps.AesParams params(
        java.util.function.UnaryOperator<CodecDevToolOps.AesParams.AesParamsBuilder> customizer) {
        return customizer.apply(CodecDevToolOps.AesParams.builder()).build();
    }
}
