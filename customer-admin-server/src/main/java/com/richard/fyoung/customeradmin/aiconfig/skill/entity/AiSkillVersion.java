package com.richard.fyoung.customeradmin.aiconfig.skill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Skill 不可变版本，冻结 SKILL.md 与附属文件集合的内容指纹。 */
@Data
@TableName("ai_skill_version")
public class AiSkillVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long skillId;
    private Integer versionNo;
    private String skillName;
    private String skillCode;
    private String content;
    private String description;
    private String contentHash;
    private String changeNote;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
