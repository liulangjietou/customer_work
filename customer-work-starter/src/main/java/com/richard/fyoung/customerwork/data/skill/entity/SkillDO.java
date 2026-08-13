package com.richard.fyoung.customerwork.data.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 技能持久化对象（贫血数据袋）：与 {@code cw_skill} 表一一映射。
 *
 * <p>{@code skillCode} 同时是物化落盘时的目录名，故有字符白名单约束（见 {@code MysqlSkillMaterializer}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_skill")
public class SkillDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String skillCode;
    private String skillName;
    /** SKILL.md 正文。 */
    private String content;
    private String description;
    /** 1 启用 / 0 停用。 */
    private Integer enabled;
    private Long createdAtMs;
    private Long updatedAtMs;
}
