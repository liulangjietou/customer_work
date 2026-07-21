package com.richard.fyoung.customerwork.tool.devtool;

import lombok.Builder;
import lombok.Getter;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 编解码与加解密纯函数集：Base64 / URL 编解码、哈希(HMAC)、UUID、AES 加解密。
 *
 * <p>无 Spring 依赖、无状态，参数非法入口 fast-fail 抛 {@link IllegalArgumentException}。
 * 所有二进制输出统一小写 hex 或 Base64 文本，便于跨系统对齐。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class CodecDevToolOps {

    /** 统一字符集。 */
    private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    /** 支持的摘要算法。 */
    private static final Set<String> SUPPORTED_HASH = Set.of("MD5", "SHA-1", "SHA-256", "SHA-512");

    /** UUID 生成数量边界。 */
    private static final int UUID_MIN_COUNT = 1;
    private static final int UUID_MAX_COUNT = 20;

    /** AES 相关常量。 */
    private static final String AES = "AES";
    private static final String TRANSFORM_CBC = "AES/CBC/PKCS5Padding";
    private static final String TRANSFORM_ECB = "AES/ECB/PKCS5Padding";
    private static final String TRANSFORM_GCM = "AES/GCM/NoPadding";
    private static final String MODE_CBC = "CBC";
    private static final String MODE_ECB = "ECB";
    private static final String MODE_GCM = "GCM";
    private static final int KEY_LEN_128 = 16;
    private static final int KEY_LEN_192 = 24;
    private static final int KEY_LEN_256 = 32;
    private static final int CBC_IV_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ---------------------------------------------------------------------
    // Base64 / URL
    // ---------------------------------------------------------------------

    /** Base64 编码（UTF-8）。允许空串。 */
    public String base64Encode(String text) {
        DevToolArgs.requireNonNull(text, "text");
        return Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
    }

    /** Base64 解码（UTF-8）。非法 Base64 抛出带说明的异常。 */
    public String base64Decode(String text) {
        DevToolArgs.requireNonBlank(text, "text");
        try {
            return new String(Base64.getDecoder().decode(text.trim()), UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base64 解码失败，输入不是合法的 Base64：" + e.getMessage(), e);
        }
    }

    /** URL 编码（application/x-www-form-urlencoded，UTF-8）。允许空串。 */
    public String urlEncode(String text) {
        DevToolArgs.requireNonNull(text, "text");
        return URLEncoder.encode(text, UTF_8);
    }

    /** URL 解码（UTF-8）。非法编码抛出带说明的异常。 */
    public String urlDecode(String text) {
        DevToolArgs.requireNonNull(text, "text");
        try {
            return URLDecoder.decode(text, UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 解码失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // Hash / HMAC
    // ---------------------------------------------------------------------

    /**
     * 计算摘要，输出小写 hex。
     *
     * @param algorithm 摘要算法：MD5 / SHA-1 / SHA-256 / SHA-512
     * @param text      待摘要文本
     * @param hmacKey   HMAC 密钥；非空时走 HmacXXX，空时走普通摘要
     * @return 小写 hex 摘要
     */
    public String hash(String algorithm, String text, String hmacKey) {
        DevToolArgs.requireNonBlank(algorithm, "algorithm");
        DevToolArgs.requireNonNull(text, "text");
        String algo = algorithm.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_HASH.contains(algo)) {
            throw new IllegalArgumentException("不支持的算法：" + algorithm + "，仅支持 MD5/SHA-1/SHA-256/SHA-512");
        }
        try {
            byte[] digest;
            if (hmacKey != null && !hmacKey.isEmpty()) {
                String macAlgo = toMacAlgorithm(algo);
                Mac mac = Mac.getInstance(macAlgo);
                mac.init(new SecretKeySpec(hmacKey.getBytes(UTF_8), macAlgo));
                digest = mac.doFinal(text.getBytes(UTF_8));
            } else {
                digest = MessageDigest.getInstance(algo).digest(text.getBytes(UTF_8));
            }
            return toHexLower(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("hash 计算失败：" + e.getMessage(), e);
        }
    }

    /** 摘要算法名映射为对应的 HMAC 算法名。 */
    private String toMacAlgorithm(String algo) {
        switch (algo) {
            case "MD5":
                return "HmacMD5";
            case "SHA-1":
                return "HmacSHA1";
            case "SHA-256":
                return "HmacSHA256";
            case "SHA-512":
                return "HmacSHA512";
            default:
                // 上游已白名单校验，正常不可达
                throw new IllegalArgumentException("不支持的算法：" + algo);
        }
    }

    // ---------------------------------------------------------------------
    // UUID
    // ---------------------------------------------------------------------

    /** 批量生成 UUID（数量 1~20）。 */
    public List<String> uuid(int count) {
        if (count < UUID_MIN_COUNT || count > UUID_MAX_COUNT) {
            throw new IllegalArgumentException("count 必须在 " + UUID_MIN_COUNT + "~" + UUID_MAX_COUNT + " 之间，当前 " + count);
        }
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(UUID.randomUUID().toString());
        }
        return list;
    }

    // ---------------------------------------------------------------------
    // AES
    // ---------------------------------------------------------------------

    /**
     * AES 加密，密文以 Base64 输出。
     *
     * @param plainText 明文（允许空串）
     * @param key       密钥，UTF-8 字节长度须为 16/24/32
     * @param mode      模式 CBC/ECB/GCM，为空默认 CBC
     * @param iv        IV(Base64)，CBC/GCM 缺省时随机生成并随结果返回；ECB 忽略
     * @return 加密结果（含 mode、密文 Base64、iv Base64）
     */
    public AesResult aesEncrypt(String plainText, String key, String mode, String iv) {
        DevToolArgs.requireNonNull(plainText, "plainText");
        byte[] keyBytes = validateKey(key);
        String normalizedMode = normalizeMode(mode);
        try {
            switch (normalizedMode) {
                case MODE_ECB: {
                    Cipher cipher = Cipher.getInstance(TRANSFORM_ECB);
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, AES));
                    byte[] out = cipher.doFinal(plainText.getBytes(UTF_8));
                    return AesResult.builder().mode(normalizedMode).ciphertext(base64(out)).build();
                }
                case MODE_GCM: {
                    byte[] ivBytes = resolveIvForEncrypt(iv, GCM_IV_LENGTH);
                    Cipher cipher = Cipher.getInstance(TRANSFORM_GCM);
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, AES), new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
                    byte[] out = cipher.doFinal(plainText.getBytes(UTF_8));
                    return AesResult.builder().mode(normalizedMode).ciphertext(base64(out)).iv(base64(ivBytes)).build();
                }
                case MODE_CBC:
                default: {
                    byte[] ivBytes = resolveIvForEncrypt(iv, CBC_IV_LENGTH);
                    Cipher cipher = Cipher.getInstance(TRANSFORM_CBC);
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, AES), new IvParameterSpec(ivBytes));
                    byte[] out = cipher.doFinal(plainText.getBytes(UTF_8));
                    return AesResult.builder().mode(normalizedMode).ciphertext(base64(out)).iv(base64(ivBytes)).build();
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * AES 解密，密文为 Base64。
     *
     * @param cipherText 密文 Base64
     * @param key        密钥，UTF-8 字节长度须为 16/24/32
     * @param mode       模式 CBC/ECB/GCM，为空默认 CBC
     * @param iv         IV(Base64)，CBC/GCM 必填；ECB 忽略
     * @return 明文
     */
    public String aesDecrypt(String cipherText, String key, String mode, String iv) {
        DevToolArgs.requireNonBlank(cipherText, "cipherText");
        byte[] keyBytes = validateKey(key);
        String normalizedMode = normalizeMode(mode);
        byte[] cipherBytes;
        try {
            cipherBytes = Base64.getDecoder().decode(cipherText.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("密文必须是合法 Base64：" + e.getMessage(), e);
        }
        try {
            Cipher cipher;
            switch (normalizedMode) {
                case MODE_ECB:
                    cipher = Cipher.getInstance(TRANSFORM_ECB);
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, AES));
                    break;
                case MODE_GCM: {
                    byte[] ivBytes = requireIvForDecrypt(iv, GCM_IV_LENGTH);
                    cipher = Cipher.getInstance(TRANSFORM_GCM);
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, AES), new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
                    break;
                }
                case MODE_CBC:
                default: {
                    byte[] ivBytes = requireIvForDecrypt(iv, CBC_IV_LENGTH);
                    cipher = Cipher.getInstance(TRANSFORM_CBC);
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, AES), new IvParameterSpec(ivBytes));
                    break;
                }
            }
            return new String(cipher.doFinal(cipherBytes), UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES 解密失败（密钥/IV/模式不匹配或密文损坏）：" + e.getMessage(), e);
        }
    }

    /** 校验并返回密钥字节（UTF-8 长度须 16/24/32）。 */
    private byte[] validateKey(String key) {
        DevToolArgs.requireNonBlank(key, "key");
        byte[] bytes = key.getBytes(UTF_8);
        if (bytes.length != KEY_LEN_128 && bytes.length != KEY_LEN_192 && bytes.length != KEY_LEN_256) {
            throw new IllegalArgumentException(
                "AES 密钥 UTF-8 字节长度必须为 16/24/32，当前 " + bytes.length + " 字节");
        }
        return bytes;
    }

    /** 规范化模式，为空默认 CBC，非法抛出。 */
    private String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return MODE_CBC;
        }
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if (!MODE_CBC.equals(upper) && !MODE_ECB.equals(upper) && !MODE_GCM.equals(upper)) {
            throw new IllegalArgumentException("mode 仅支持 CBC/ECB/GCM，当前 " + mode);
        }
        return upper;
    }

    /** 加密取 IV：缺省随机生成，提供则按 Base64 解码并校验长度。 */
    private byte[] resolveIvForEncrypt(String iv, int expectedLen) {
        if (iv == null || iv.trim().isEmpty()) {
            byte[] generated = new byte[expectedLen];
            new SecureRandom().nextBytes(generated);
            return generated;
        }
        return decodeIv(iv, expectedLen);
    }

    /** 解密取 IV：必填，缺省抛出，提供则按 Base64 解码并校验长度。 */
    private byte[] requireIvForDecrypt(String iv, int expectedLen) {
        if (iv == null || iv.trim().isEmpty()) {
            throw new IllegalArgumentException("该模式解密必须提供 IV（Base64，" + expectedLen + " 字节）");
        }
        return decodeIv(iv, expectedLen);
    }

    /** Base64 解码 IV 并校验字节长度。 */
    private byte[] decodeIv(String iv, int expectedLen) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(iv.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("IV 必须是合法 Base64：" + e.getMessage(), e);
        }
        if (decoded.length != expectedLen) {
            throw new IllegalArgumentException("IV 长度必须为 " + expectedLen + " 字节，当前 " + decoded.length + " 字节");
        }
        return decoded;
    }

    private String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 字节数组转小写 hex。 */
    private String toHexLower(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }

    /**
     * AES 结果。ECB 模式 iv 为 null。
     */
    @Getter
    @Builder
    public static class AesResult {
        /** 实际采用的模式。 */
        private final String mode;
        /** 密文 Base64。 */
        private final String ciphertext;
        /** IV Base64（ECB 时为 null）。 */
        private final String iv;
    }
}
