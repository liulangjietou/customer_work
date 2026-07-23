package com.richard.fyoung.customeradmin.aiconfig.skill.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 附属文件（zip 上传的 references/scripts/examples 等，SKILL.md 本体仍存 {@code ai_skill.content}）。
 *
 * <p>随 Skill 保存全量替换、物理删除（不用逻辑删除，避免 LONGBLOB 死数据堆积），
 * 构建智能体实例时与 SKILL.md 一起落盘供 FileSystemSkillRepository 加载。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_skill_file")
public class AiSkillFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属 Skill（ai_skill.id）。 */
    private Long skillId;
    /** 相对 SKILL.md 所在目录的路径，如 {@code references/api.md}。 */
    private String filePath;
    /** 文件字节数。 */
    private Long fileSize;
    /** 文件内容（文本/二进制统一按字节存）。 */
    private byte[] content;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
