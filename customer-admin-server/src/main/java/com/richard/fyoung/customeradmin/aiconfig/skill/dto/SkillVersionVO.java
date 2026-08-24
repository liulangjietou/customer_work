package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Skill 不可变版本摘要。 */
@Data
public class SkillVersionVO {
    private Long id;
    private Integer versionNo;
    private String contentHash;
    private String changeNote;
    private LocalDateTime createTime;
}
