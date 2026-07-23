package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

import java.util.List;

/**
 * parse-upload 解析结果：SKILL.md 正文 + zip 内全部附属文件。
 *
 * <p>不落库——前端拿到后回填表单并暂存 files，仍走 create/update 一并保存；
 * {@code .md} 直传时 files 为空列表。</p>
 * @author owlzhangfq@gmail.com
 */
public record SkillUploadParseResult(
    String content,
    List<SkillUploadFile> files) {
}
