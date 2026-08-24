package com.richard.fyoung.customerchannel.access.wechat;

import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/** 微信公众号安全模式消息解密与 AppID 归属校验。 */
final class WeChatMessageCrypto {

    private static final int ENCODING_AES_KEY_LENGTH = 43;
    private static final int AES_KEY_BYTES = 32;
    private static final int IV_BYTES = 16;
    private static final int RANDOM_PREFIX_BYTES = 16;
    private static final int LENGTH_BYTES = 4;
    private static final int PKCS7_BLOCK_SIZE = 32;

    private WeChatMessageCrypto() {
    }

    static String decrypt(String encodingAesKey, String encrypted, String expectedAppId) {
        if (!StringUtils.hasText(encodingAesKey)
            || encodingAesKey.length() != ENCODING_AES_KEY_LENGTH
            || !StringUtils.hasText(encrypted)
            || !StringUtils.hasText(expectedAppId)) {
            throw new IllegalArgumentException("invalid wechat safe mode parameters");
        }
        try {
            byte[] aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
            if (aesKey.length != AES_KEY_BYTES) {
                throw new IllegalArgumentException("invalid wechat EncodingAESKey");
            }
            byte[] cipherText = Base64.getDecoder().decode(encrypted);
            if (cipherText.length == 0 || cipherText.length % IV_BYTES != 0) {
                throw new IllegalArgumentException("invalid wechat encrypted payload");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                new IvParameterSpec(aesKey, 0, IV_BYTES));
            byte[] plain = removePkcs7Padding(cipher.doFinal(cipherText));
            return extractPayload(plain, expectedAppId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("wechat safe mode decrypt failed", e);
        }
    }

    private static byte[] removePkcs7Padding(byte[] input) {
        if (input.length == 0) {
            throw new IllegalArgumentException("invalid wechat safe mode padding");
        }
        int padding = input[input.length - 1] & 0xff;
        if (padding < 1 || padding > PKCS7_BLOCK_SIZE || padding > input.length) {
            throw new IllegalArgumentException("invalid wechat safe mode padding");
        }
        int mismatch = 0;
        for (int i = input.length - padding; i < input.length; i++) {
            mismatch |= (input[i] & 0xff) ^ padding;
        }
        if (mismatch != 0) {
            throw new IllegalArgumentException("invalid wechat safe mode padding");
        }
        return Arrays.copyOf(input, input.length - padding);
    }

    private static String extractPayload(byte[] plain, String expectedAppId) {
        int headerBytes = RANDOM_PREFIX_BYTES + LENGTH_BYTES;
        if (plain.length < headerBytes) {
            throw new IllegalArgumentException("invalid wechat safe mode payload");
        }
        int payloadLength = ByteBuffer.wrap(plain, RANDOM_PREFIX_BYTES, LENGTH_BYTES).getInt();
        int appIdOffset = headerBytes + payloadLength;
        if (payloadLength < 0 || appIdOffset < headerBytes || appIdOffset > plain.length) {
            throw new IllegalArgumentException("invalid wechat safe mode payload length");
        }
        byte[] actualAppId = Arrays.copyOfRange(plain, appIdOffset, plain.length);
        byte[] expectedAppIdBytes = expectedAppId.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedAppIdBytes, actualAppId)) {
            throw new IllegalArgumentException("wechat safe mode appId mismatch");
        }
        return new String(plain, headerBytes, payloadLength, StandardCharsets.UTF_8);
    }
}
