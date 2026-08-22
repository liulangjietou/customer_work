package com.richard.fyoung.customerwork.infra.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Admin 发布端与 Starter 消费端共用的运行时配置内容摘要算法。
 *
 * <p>{@code publishedAt}/{@code revision}/{@code contentHash} 是投递元数据，不属于业务配置正文。
 * 计算时临时清空这三个字段，并使用调用端当前 {@link ObjectMapper} 序列化；完成后恢复原对象。</p>
 */
public final class RuntimeConfigContentHasher {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    private RuntimeConfigContentHasher() {
    }

    public static String compute(CustomerWorkRuntimeConfig config, ObjectMapper objectMapper) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(objectMapper, "objectMapper");
        String publishedAt = config.getPublishedAt();
        String revision = config.getRevision();
        String contentHash = config.getContentHash();
        try {
            config.setPublishedAt(null);
            config.setRevision(null);
            config.setContentHash(null);
            return sha256(objectMapper.writeValueAsString(config));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("runtime config content serialization failed", e);
        } finally {
            config.setPublishedAt(publishedAt);
            config.setRevision(revision);
            config.setContentHash(contentHash);
        }
    }

    /** 摘要必须是无前后空白的 64 位 SHA-256 十六进制文本。 */
    public static boolean isValidFormat(String contentHash) {
        return contentHash != null && SHA_256_HEX.matcher(contentHash).matches();
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
