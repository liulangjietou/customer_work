package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class SkillVO {
    private Long id;
    private String skillName;
    private String skillCode;
    private String content;
    private String description;
    private Integer status;
    private LocalDateTime createTime;
}
