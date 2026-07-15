package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Skill 新建/编辑请求。
 * @author owlzhangfq@gmail.com
 */
public record SkillSaveRequest(
    @NotBlank(message = "skillName 不能为空") String skillName,
    @NotBlank(message = "skillCode 不能为空") String skillCode,
    @NotBlank(message = "content 不能为空") String content,
    String description,
    Integer status,
    /** 存储目标：local/nacos/sftp 多选，空/null 时默认 ["local"]。 */
    List<String> storageTargets) {
}
