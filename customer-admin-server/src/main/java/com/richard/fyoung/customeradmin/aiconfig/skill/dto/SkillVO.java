package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    /** 存储目标：local/nacos/sftp。 */
    private List<String> storageTargets;
    /** 附属文件清单（不含内容），zip 上传的 references/scripts 等。 */
    private List<SkillFileVO> files;
    private LocalDateTime createTime;
}
