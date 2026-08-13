package com.richard.fyoung.customerwork.data.skill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 技能附属文件持久化对象（贫血数据袋）：与 {@code cw_skill_file} 表一一映射。
 *
 * <p>SKILL.md 正文存 {@code cw_skill.content}，本表放它引用的 references/scripts/examples——
 * 这些不落盘的话技能在运行时是残的（引用路径不存在）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_skill_file")
public class SkillFileDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long skillId;
    /** 相对 SKILL.md 所在目录的路径，如 {@code references/api.md}。 */
    private String filePath;
    private Long fileSize;
    /** 文件内容（文本/二进制统一按字节存）。 */
    private byte[] content;
    private Long createdAtMs;
}
