package com.richard.fyoung.customerwork.safety.subjectquota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 限流主体（不可变值对象）：一次请求要算在谁的额度上。
 *
 * <p>由接入层解析（登录态 → 用户、API Key → 接入方、都没有 → IP），随请求贯穿到 token 记账点，
 * 见 {@link QuotaSubjectContext}。</p>
 *
 * <p><b>API Key 一律只留指纹</b>：主体 ID 会进 Redis 计数键、进超限记录表、进日志，
 * 明文 Key 落到任何一处都是凭据泄露。指纹取 SHA-256 前 16 位十六进制——够长到不会碰撞，
 * 又短到能直接放进 Redis key。</p>
 *
 * @param type 主体类型
 * @param id   主体标识（用户 ID / IP / API Key 指纹）
 * @author owlzhangfq@gmail.com
 */
public record QuotaSubject(QuotaSubjectType type, String id) {

    /** 主体标识缺失时的占位：解析不出身份也要有一份额度可算，否则等于放行。 */
    public static final String UNKNOWN_ID = "unknown";

    private static final String KEY_PREFIX = "squota:";
    private static final int FINGERPRINT_HEX_LEN = 16;

    public static QuotaSubject user(String userId) {
        return new QuotaSubject(QuotaSubjectType.USER, blankToUnknown(userId));
    }

    /** 后台管理系统的登录用户（Sa-Token 登录 ID）。 */
    public static QuotaSubject adminUser(String adminUserId) {
        return new QuotaSubject(QuotaSubjectType.ADMIN_USER, blankToUnknown(adminUserId));
    }

    public static QuotaSubject ip(String ip) {
        return new QuotaSubject(QuotaSubjectType.IP, blankToUnknown(ip));
    }

    /** 从明文 API Key 构造：只保留指纹，明文不落入本对象。 */
    public static QuotaSubject apiKey(String rawApiKey) {
        return new QuotaSubject(QuotaSubjectType.API_KEY, fingerprint(rawApiKey));
    }

    /** 计数键前缀（token 与次数各自再加后缀，见 {@link SubjectQuotaGuard}）。 */
    public String counterKey() {
        return KEY_PREFIX + type.name() + ":" + id;
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN_ID : value;
    }

    /**
     * API Key 指纹。
     *
     * <p>取不到摘要算法时退回明文的哈希码：宁可主体粒度粗一点，也不能因为一个基础算法缺失
     * 就让整条限流链路抛异常——那是把保护性能力变成故障源。</p>
     */
    private static String fingerprint(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return UNKNOWN_ID;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX_LEN);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(rawApiKey.hashCode());
        }
    }
}
