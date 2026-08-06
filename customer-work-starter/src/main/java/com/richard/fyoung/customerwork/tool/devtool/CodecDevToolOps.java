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
 * 编解码与加解密纯函数集：Base64 / URL / Hex 编解码、哈希(HMAC)、UUID、AES 加解密。
 *
 * <p>无 Spring 依赖、无状态，参数非法入口 fast-fail 抛 {@link IllegalArgumentException}。
 * 所有二进制输出统一小写 hex 或 Base64 文本，便于跨系统对齐。</p>
 *
 * <p><b>AES 参数集与管理台页面版的对齐</b>：管理台"开发者工具箱 → AES 加解密"页面用 crypto-js
 * 在浏览器本地算（密钥不出浏览器，刻意不走后端），因此 AES 是整个工具箱里唯一存在两套实现的能力。
 * 为保证"页面加密的密文智能体一定能解、反之亦然"，本实现刻意做成页面版的<b>严格超集</b>：
 * 模式覆盖 CBC/ECB/CTR（页面版全集）并额外支持 GCM，填充、密钥/IV 的文本编码、密文输出格式
 * 也全部与页面版一样可选。唯一不对等的是 GCM——crypto-js 不提供 GCM，页面版解不了智能体产出的
 * GCM 密文，故 GCM 只适用于两端都在后端的场景。</p>
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
    private static final String MODE_CBC = "CBC";
    private static final String MODE_ECB = "ECB";
    private static final String MODE_GCM = "GCM";
    private static final String MODE_CTR = "CTR";
    /** JCE 填充名：AES 场景下 PKCS5Padding 与 PKCS7 等价（块长固定 16）。 */
    private static final String JCE_PADDING_PKCS7 = "PKCS5Padding";
    private static final String JCE_PADDING_NONE = "NoPadding";
    /** 对外的填充取值（与页面版的选项一一对应）。 */
    private static final String PADDING_PKCS7 = "PKCS7";
    private static final String PADDING_NONE = "NONE";
    private static final int KEY_LEN_128 = 16;
    private static final int KEY_LEN_192 = 24;
    private static final int KEY_LEN_256 = 32;
    /** 分组模式的 IV 长度即 AES 块长；GCM 用推荐的 12 字节 nonce。 */
    private static final int BLOCK_IV_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    /** AES 块长，NoPadding 时明文长度须为其整数倍。 */
    private static final int AES_BLOCK_SIZE = 16;

    /** 二进制内容的文本编码（用于密钥/IV 的解析与密文的输出）。 */
    private static final String ENCODING_UTF8 = "UTF8";
    private static final String ENCODING_HEX = "HEX";
    private static final String ENCODING_BASE64 = "BASE64";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ---------------------------------------------------------------------
    // Base64 / URL / Hex
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

    /** 文本转小写 hex（按 UTF-8 取字节）。允许空串。 */
    public String hexEncode(String text) {
        DevToolArgs.requireNonNull(text, "text");
        return toHexLower(text.getBytes(UTF_8));
    }

    /** hex 转文本（按 UTF-8 解码）。忽略空白字符；长度为奇数或含非 hex 字符均抛出。 */
    public String hexDecode(String hex) {
        DevToolArgs.requireNonNull(hex, "hex");
        return new String(decodeHex(hex, "hex"), UTF_8);
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
     * AES 加密。
     *
     * @param plainText 明文（允许空串）
     * @param params    密钥、模式、填充、编码等参数，见 {@link AesParams}
     * @return 加密结果（含实际生效的模式与填充、密文、IV；密文与 IV 按 outputFormat 编码）
     */
    public AesResult aesEncrypt(String plainText, AesParams params) {
        DevToolArgs.requireNonNull(plainText, "plainText");
        DevToolArgs.requireNonNull(params, "params");
        ResolvedAesParams resolved = resolve(params);
        byte[] plainBytes = plainText.getBytes(UTF_8);
        if (JCE_PADDING_NONE.equals(resolved.jcePadding) && isBlockMode(resolved.mode)
            && plainBytes.length % AES_BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("NoPadding 下明文 UTF-8 字节长度必须是 "
                + AES_BLOCK_SIZE + " 的整数倍，当前 " + plainBytes.length + " 字节");
        }
        byte[] ivBytes = MODE_ECB.equals(resolved.mode)
            ? null
            : resolveIvForEncrypt(params.getIv(), resolved.ivEncoding, ivLengthOf(resolved.mode));
        try {
            Cipher cipher = Cipher.getInstance(resolved.transform);
            initCipher(cipher, Cipher.ENCRYPT_MODE, resolved, ivBytes);
            byte[] out = cipher.doFinal(plainBytes);
            return AesResult.builder()
                .mode(resolved.mode)
                .padding(toExternalPadding(resolved.jcePadding))
                .outputFormat(resolved.outputFormat.toLowerCase(Locale.ROOT))
                .ciphertext(encodeBinary(out, resolved.outputFormat))
                .iv(ivBytes == null ? null : encodeBinary(ivBytes, resolved.ivEncoding))
                .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * AES 解密。
     *
     * @param cipherText 密文（按 outputFormat 指定的格式，hex 或 Base64）
     * @param params     密钥、模式、填充、编码等参数，须与加密时完全一致
     * @return 明文
     */
    public String aesDecrypt(String cipherText, AesParams params) {
        DevToolArgs.requireNonBlank(cipherText, "cipherText");
        DevToolArgs.requireNonNull(params, "params");
        ResolvedAesParams resolved = resolve(params);
        byte[] cipherBytes = decodeBinary(cipherText, resolved.outputFormat, "cipherText");
        byte[] ivBytes = MODE_ECB.equals(resolved.mode)
            ? null
            : requireIvForDecrypt(params.getIv(), resolved.ivEncoding, ivLengthOf(resolved.mode));
        try {
            Cipher cipher = Cipher.getInstance(resolved.transform);
            initCipher(cipher, Cipher.DECRYPT_MODE, resolved, ivBytes);
            return new String(cipher.doFinal(cipherBytes), UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("AES 解密失败（密钥/IV/模式/填充不匹配或密文损坏）：" + e.getMessage(), e);
        }
    }

    /** 按模式装配 Cipher 参数（ECB 无 IV、GCM 走 GCMParameterSpec、其余走 IvParameterSpec）。 */
    private void initCipher(Cipher cipher, int opmode, ResolvedAesParams resolved, byte[] ivBytes)
        throws GeneralSecurityException {
        SecretKeySpec keySpec = new SecretKeySpec(resolved.keyBytes, AES);
        if (MODE_ECB.equals(resolved.mode)) {
            cipher.init(opmode, keySpec);
        } else if (MODE_GCM.equals(resolved.mode)) {
            cipher.init(opmode, keySpec, new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
        } else {
            cipher.init(opmode, keySpec, new IvParameterSpec(ivBytes));
        }
    }

    /** 归一化并校验全部 AES 参数，一次算清 transform 与密钥字节。 */
    private ResolvedAesParams resolve(AesParams params) {
        ResolvedAesParams resolved = new ResolvedAesParams();
        resolved.mode = normalizeMode(params.getMode());
        resolved.jcePadding = resolvePadding(params.getPadding(), resolved.mode);
        resolved.transform = AES + "/" + resolved.mode + "/" + resolved.jcePadding;
        resolved.ivEncoding = normalizeEncoding(params.getIvEncoding(), ENCODING_BASE64, "ivEncoding");
        resolved.outputFormat = normalizeOutputFormat(params.getOutputFormat());
        resolved.keyBytes = validateKey(params.getKey(),
            normalizeEncoding(params.getKeyEncoding(), ENCODING_UTF8, "keyEncoding"));
        return resolved;
    }

    /** 校验并返回密钥字节（按指定编码解码后长度须 16/24/32）。 */
    private byte[] validateKey(String key, String encoding) {
        DevToolArgs.requireNonBlank(key, "key");
        byte[] bytes = decodeBinary(key, encoding, "key");
        if (bytes.length != KEY_LEN_128 && bytes.length != KEY_LEN_192 && bytes.length != KEY_LEN_256) {
            throw new IllegalArgumentException("AES 密钥按 " + encoding
                + " 解码后字节长度必须为 16/24/32，当前 " + bytes.length + " 字节");
        }
        return bytes;
    }

    /** 规范化模式，为空默认 CBC，非法抛出。 */
    private String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return MODE_CBC;
        }
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if (!MODE_CBC.equals(upper) && !MODE_ECB.equals(upper) && !MODE_GCM.equals(upper) && !MODE_CTR.equals(upper)) {
            throw new IllegalArgumentException("mode 仅支持 CBC/ECB/CTR/GCM，当前 " + mode);
        }
        return upper;
    }

    /**
     * 解析填充方式并与模式做相容性检查。
     *
     * <p>缺省时按模式取默认：分组模式(CBC/ECB)默认 PKCS7，流式/认证模式(CTR/GCM)默认无填充。
     * CTR/GCM 显式指定 PKCS7 属于配置错误，直接 fast-fail 而非静默忽略——静默忽略会让调用方
     * 以为参数生效了，等到密文对不上时反而更难排查。</p>
     */
    private String resolvePadding(String padding, String mode) {
        boolean blockMode = isBlockMode(mode);
        if (padding == null || padding.trim().isEmpty()) {
            return blockMode ? JCE_PADDING_PKCS7 : JCE_PADDING_NONE;
        }
        String upper = padding.trim().toUpperCase(Locale.ROOT).replace("PADDING", "").replace("_", "");
        if (upper.isEmpty() || PADDING_NONE.equals(upper)) {
            return JCE_PADDING_NONE;
        }
        // PKCS5 与 PKCS7 在 AES(块长 16) 下等价，两种写法都收
        if (PADDING_PKCS7.equals(upper) || "PKCS5".equals(upper)) {
            if (!blockMode) {
                throw new IllegalArgumentException(mode + " 是流式/认证加密模式，不使用块填充，padding 只能为 NONE 或留空");
            }
            return JCE_PADDING_PKCS7;
        }
        throw new IllegalArgumentException("padding 仅支持 PKCS7 或 NONE，当前 " + padding);
    }

    /** 是否为需要块填充的分组模式。 */
    private boolean isBlockMode(String mode) {
        return MODE_CBC.equals(mode) || MODE_ECB.equals(mode);
    }

    /** JCE 填充名转对外取值。 */
    private String toExternalPadding(String jcePadding) {
        return JCE_PADDING_PKCS7.equals(jcePadding) ? PADDING_PKCS7 : PADDING_NONE;
    }

    /** 各模式的 IV 字节长度（ECB 不使用 IV，不会走到这里）。 */
    private int ivLengthOf(String mode) {
        return MODE_GCM.equals(mode) ? GCM_IV_LENGTH : BLOCK_IV_LENGTH;
    }

    /** 规范化二进制文本编码，为空取默认值。 */
    private String normalizeEncoding(String encoding, String defaultEncoding, String fieldName) {
        if (encoding == null || encoding.trim().isEmpty()) {
            return defaultEncoding;
        }
        String upper = encoding.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (!ENCODING_UTF8.equals(upper) && !ENCODING_HEX.equals(upper) && !ENCODING_BASE64.equals(upper)) {
            throw new IllegalArgumentException(fieldName + " 仅支持 utf8/hex/base64，当前 " + encoding);
        }
        return upper;
    }

    /** 规范化密文输入输出格式，为空默认 base64（密文是二进制，不能按 utf8 呈现）。 */
    private String normalizeOutputFormat(String outputFormat) {
        if (outputFormat == null || outputFormat.trim().isEmpty()) {
            return ENCODING_BASE64;
        }
        String upper = outputFormat.trim().toUpperCase(Locale.ROOT);
        if (!ENCODING_HEX.equals(upper) && !ENCODING_BASE64.equals(upper)) {
            throw new IllegalArgumentException("outputFormat 仅支持 hex/base64，当前 " + outputFormat);
        }
        return upper;
    }

    /** 按指定编码把文本解析成字节。 */
    private byte[] decodeBinary(String raw, String encoding, String fieldName) {
        switch (encoding) {
            case ENCODING_UTF8:
                return raw.getBytes(UTF_8);
            case ENCODING_HEX:
                return decodeHex(raw, fieldName);
            case ENCODING_BASE64:
            default:
                try {
                    return Base64.getDecoder().decode(raw.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(fieldName + " 不是合法的 Base64：" + e.getMessage(), e);
                }
        }
    }

    /** 按指定编码把字节输出成文本（仅 hex/base64；utf8 用于密钥输入，不用于输出）。 */
    private String encodeBinary(byte[] bytes, String encoding) {
        return ENCODING_HEX.equals(encoding) ? toHexLower(bytes) : Base64.getEncoder().encodeToString(bytes);
    }

    /** hex 文本转字节：忽略空白，校验字符集与偶数长度。 */
    private byte[] decodeHex(String raw, String fieldName) {
        String cleaned = raw.replaceAll("\\s", "");
        if (cleaned.isEmpty()) {
            return new byte[0];
        }
        if (!cleaned.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException(fieldName + " 含非十六进制字符（只允许 0-9、a-f、A-F，空白会被忽略）");
        }
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException(fieldName + " 的十六进制长度必须是偶数（每两位一个字节），当前 "
                + cleaned.length() + " 位");
        }
        byte[] bytes = new byte[cleaned.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /** 加密取 IV：缺省随机生成，提供则按指定编码解码并校验长度。 */
    private byte[] resolveIvForEncrypt(String iv, String encoding, int expectedLen) {
        if (iv == null || iv.trim().isEmpty()) {
            byte[] generated = new byte[expectedLen];
            new SecureRandom().nextBytes(generated);
            return generated;
        }
        return decodeIv(iv, encoding, expectedLen);
    }

    /** 解密取 IV：必填，缺省抛出，提供则按指定编码解码并校验长度。 */
    private byte[] requireIvForDecrypt(String iv, String encoding, int expectedLen) {
        if (iv == null || iv.trim().isEmpty()) {
            throw new IllegalArgumentException("该模式解密必须提供 IV（" + expectedLen + " 字节，按 ivEncoding 编码）");
        }
        return decodeIv(iv, encoding, expectedLen);
    }

    /** 解码 IV 并校验字节长度。 */
    private byte[] decodeIv(String iv, String encoding, int expectedLen) {
        byte[] decoded = decodeBinary(iv, encoding, "iv");
        if (decoded.length != expectedLen) {
            throw new IllegalArgumentException("IV 按 " + encoding + " 解码后长度必须为 " + expectedLen
                + " 字节，当前 " + decoded.length + " 字节");
        }
        return decoded;
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

    /** 归一化后的 AES 参数（内部载体，避免在各方法间反复重算与传一长串参数）。 */
    private static final class ResolvedAesParams {
        private String mode;
        private String jcePadding;
        private String transform;
        private byte[] keyBytes;
        private String ivEncoding;
        private String outputFormat;
    }

    /**
     * AES 参数。可选项留空即取默认值，默认值刻意保持本工具早期版本的行为
     * （CBC + PKCS7 + 密钥 utf8 + IV base64 + 密文 base64），确保既有调用方升级后密文仍对得上。
     */
    @Getter
    @Builder
    public static class AesParams {
        /** 密钥（按 keyEncoding 解码后须为 16/24/32 字节）。 */
        private final String key;
        /** 密钥的文本编码：utf8(默认)/hex/base64。 */
        private final String keyEncoding;
        /** 模式：CBC(默认)/ECB/CTR/GCM。 */
        private final String mode;
        /** 填充：PKCS7 / NONE；留空按模式取默认（CBC、ECB 用 PKCS7，CTR、GCM 无填充）。 */
        private final String padding;
        /** IV：ECB 不用；加密时留空则随机生成，解密时必填。 */
        private final String iv;
        /** IV 的文本编码：utf8/hex/base64(默认)。 */
        private final String ivEncoding;
        /** 密文的输入/输出格式：hex / base64(默认)。 */
        private final String outputFormat;
    }

    /**
     * AES 加密结果。ECB 模式 iv 为 null。
     */
    @Getter
    @Builder
    public static class AesResult {
        /** 实际采用的模式。 */
        private final String mode;
        /** 实际采用的填充（PKCS7 / NONE）。 */
        private final String padding;
        /** 密文的编码格式（hex / base64）。 */
        private final String outputFormat;
        /** 密文。 */
        private final String ciphertext;
        /** IV（按 ivEncoding 编码；ECB 时为 null）。 */
        private final String iv;
    }
}
