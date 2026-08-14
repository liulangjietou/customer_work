package com.richard.fyoung.customerwork.capability.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 提示词版本快照（不可变）。
 *
 * <p><b>版本号取内容指纹而非外部版本号</b>：提示词经 Nacos 下发的是<b>内容</b>，没有随行的版本号；
 * 要求发布方额外传一个版本号，就等于把"版本对不对得上"寄托在发布方每次都记得传、且传得对。
 * 内容指纹没有这个问题——内容变了指纹必变，内容没变指纹必同，跨环境也稳定。</p>
 *
 * <p>这正是效果归因的支点：某次评测指标掉了，比一下两次运行的指纹就知道<b>是不是提示词的锅</b>。
 * 指纹相同而指标变了，说明该去查模型或数据，不必再对着提示词逐字看。</p>
 *
 * @param fingerprint 内容指纹（SHA-256 十六进制前 16 位）
 * @param content     提示词全文
 * @param length      全文字符数（列表页展示用，避免每行都拖全文）
 * @param capturedAtMs 首次观测到该版本的时间戳（毫秒）
 * @author owlzhangfq@gmail.com
 */
public record PromptVersion(
    String fingerprint,
    String content,
    int length,
    long capturedAtMs
) {

    /**
     * 指纹取前 16 位十六进制。
     *
     * <p>64 位全量指纹在界面上没人看得下去；16 位（64 bit）的碰撞概率对"一个系统历史上有过多少版提示词"
     * 这个量级来说可以忽略。</p>
     */
    private static final int FINGERPRINT_LENGTH = 16;

    /** 按内容构造版本快照。 */
    public static PromptVersion of(String content, long capturedAtMs) {
        String normalized = content == null ? "" : content;
        return new PromptVersion(fingerprintOf(normalized), normalized, normalized.length(), capturedAtMs);
    }

    /** 计算内容指纹；内容为空时返回空串（表示"没有提示词"，与任何真实版本都不同）。 */
    public static String fingerprintOf(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；真走到了说明 JRE 被裁剪过，属于部署事故
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
