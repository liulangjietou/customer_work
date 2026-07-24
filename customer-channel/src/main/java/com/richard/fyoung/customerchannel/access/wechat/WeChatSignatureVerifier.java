package com.richard.fyoung.customerchannel.access.wechat;

import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 微信公众号消息签名校验。
 *
 * <p>公众平台「接口配置信息」里配置的 <b>Token</b>（本项目里映射到机器人配置的 {@code robotCode} 字段，
 * 微信语义下 robotCode = 回调 Token）参与签名：把 {@code token/timestamp/nonce} 三者按<b>字典序</b>排序后
 * 直接拼接，取 SHA-1 十六进制小写，与请求参数 {@code signature} 比对相等即校验通过。GET（接口配置验证）
 * 与 POST（消息推送）都走同一套校验。</p>
 * @author owlzhangfq@gmail.com
 */
final class WeChatSignatureVerifier {

    private WeChatSignatureVerifier() {
    }

    /**
     * 校验微信回调签名。
     *
     * @param token     公众平台配置的 Token（= 机器人配置的 robotCode）
     * @param timestamp 请求参数 timestamp
     * @param nonce     请求参数 nonce
     * @param signature 请求参数 signature（待校验）
     * @return 签名一致返回 {@code true}
     */
    static boolean verify(String token, String timestamp, String nonce, String signature) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(signature)) {
            return false;
        }
        String[] arr = {emptyIfNull(token), emptyIfNull(timestamp), emptyIfNull(nonce)};
        Arrays.sort(arr);
        String expected = sha1Hex(arr[0] + arr[1] + arr[2]);
        return expected.equalsIgnoreCase(signature);
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    /** SHA-1 十六进制小写。 */
    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是 JDK 标配算法，不可达；fast fail
            throw new IllegalStateException("SHA-1 algorithm unavailable", e);
        }
    }
}
