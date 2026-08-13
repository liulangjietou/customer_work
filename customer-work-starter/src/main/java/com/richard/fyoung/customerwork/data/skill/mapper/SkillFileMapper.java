package com.richard.fyoung.customerwork.data.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.skill.entity.SkillFileDO;

/**
 * 技能附属文件 Mapper：单表 CRUD 足够（按 skillId 查询走 LambdaQueryWrapper），无复杂 SQL 故无 XML。
 * @author owlzhangfq@gmail.com
 */
public interface SkillFileMapper extends BaseMapper<SkillFileDO> {
}
