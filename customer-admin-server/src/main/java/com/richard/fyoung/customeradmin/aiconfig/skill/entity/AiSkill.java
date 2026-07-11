package com.richard.fyoung.customeradmin.aiconfig.skill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 配置。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_skill")
public class AiSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillName;
    private String skillCode;
    /** SKILL.md 内容。 */
    private String content;
    private String description;
    /** 0禁用 / 1启用。 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
