package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

/**
 * Skill 附属文件展示项（列表/详情用，不带文件内容）。
 * @author owlzhangfq@gmail.com
 */
public record SkillFileVO(
    String filePath,
    Long fileSize) {
}
