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
    /** 存储目标，逗号分隔（如 {@code "local,nacos"}），仿 ai_agent.capabilities 的存法。 */
    private String storageTargets;
    /** 当前最新不可变版本。Agent 关系会冻结该版本 ID，不跟随此指针漂移。 */
    private Long currentVersionId;
    private Integer latestVersionNo;

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
