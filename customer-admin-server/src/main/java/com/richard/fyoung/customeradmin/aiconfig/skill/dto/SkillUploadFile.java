package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

/**
 * Skill 附属文件传输载体（parse-upload 响应与保存请求共用）。
 *
 * <p>文本/二进制统一走 base64，避免 JSON 传输破坏字节；{@code filePath} 是相对 SKILL.md
 * 所在目录的路径（如 {@code references/api.md}），路径合法性在 {@code SkillService} 统一校验。</p>
 * @author owlzhangfq@gmail.com
 */
public record SkillUploadFile(
    String filePath,
    Long fileSize,
    String contentBase64) {
}
