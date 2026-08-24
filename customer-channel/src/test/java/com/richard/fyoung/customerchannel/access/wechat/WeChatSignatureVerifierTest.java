package com.richard.fyoung.customerchannel.access.wechat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeChatSignatureVerifier} 验签测试：正确向量通过、大小写不敏感、错误签名/缺参失败。
 * @author owlzhangfq@gmail.com
 */
class WeChatSignatureVerifierTest {

    private static final String TOKEN = "mytoken";
    private static final String TIMESTAMP = "1234567890";
    private static final String NONCE = "abcnonce";

    /** 独立实现的参考签名（字典序排序拼接后 SHA-1 小写），与被测逻辑解耦。 */
    private static String expectedSignature(String token, String timestamp, String nonce) throws Exception {
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest((arr[0] + arr[1] + arr[2]).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void shouldPassWithCorrectSignature() throws Exception {
        String sig = expectedSignature(TOKEN, TIMESTAMP, NONCE);

        assertTrue(WeChatSignatureVerifier.verify(TOKEN, TIMESTAMP, NONCE, sig));
    }

    @Test
    void shouldPassCaseInsensitive() throws Exception {
        String sig = expectedSignature(TOKEN, TIMESTAMP, NONCE).toUpperCase();

        assertTrue(WeChatSignatureVerifier.verify(TOKEN, TIMESTAMP, NONCE, sig));
    }

    @Test
    void shouldFailWithWrongSignature() {
        assertFalse(WeChatSignatureVerifier.verify(TOKEN, TIMESTAMP, NONCE, "deadbeef"));
    }

    @Test
    void shouldFailWithBlankTokenOrSignature() throws Exception {
        String sig = expectedSignature(TOKEN, TIMESTAMP, NONCE);

        assertFalse(WeChatSignatureVerifier.verify("", TIMESTAMP, NONCE, sig));
        assertFalse(WeChatSignatureVerifier.verify(TOKEN, TIMESTAMP, NONCE, ""));
    }

    @Test
    void safeModeShouldIncludeEncryptedPayloadInSignature() throws Exception {
        String encrypted = "base64-encrypted-payload";
        String[] parts = {TOKEN, TIMESTAMP, NONCE, encrypted};
        Arrays.sort(parts);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
        StringBuilder signature = new StringBuilder();
        for (byte b : digest) {
            signature.append(String.format("%02x", b));
        }

        assertTrue(WeChatSignatureVerifier.verifySafe(TOKEN, TIMESTAMP, NONCE,
            encrypted, signature.toString()));
        assertFalse(WeChatSignatureVerifier.verifySafe(TOKEN, TIMESTAMP, NONCE,
            encrypted + "changed", signature.toString()));
    }
}
