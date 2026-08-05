package com.richard.fyoung.customerwork.skill.storage;

/**
 * 发布到存储目标的附属文件（路径 + 原始字节），供 {@link SkillContentPublisher#publishFiles} 使用。
 *
 * <p>{@code filePath} 是相对 skill 目录的路径（如 {@code references/api.md}），
 * 合法性由调用方（如 admin 的 {@code SkillService}）在入库前统一校验，发布器只管写出。</p>
 * @author owlzhangfq@gmail.com
 */
public record SkillFileContent(
    String filePath,
    byte[] content) {
}
