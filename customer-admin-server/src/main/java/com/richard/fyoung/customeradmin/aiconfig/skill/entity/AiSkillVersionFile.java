package com.richard.fyoung.customeradmin.aiconfig.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Skill 不可变版本中的附属文件。 */
@Data
@TableName("ai_skill_version_file")
public class AiSkillVersionFile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long skillVersionId;
    private String filePath;
    private Long fileSize;
    private byte[] content;
    private String contentHash;
    private LocalDateTime createTime;
}
