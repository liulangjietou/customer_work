package com.richard.fyoung.customerwork.capability.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.prompt.entity.PromptVersionDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 提示词版本 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 * @author owlzhangfq@gmail.com
 */
public interface PromptVersionMapper extends BaseMapper<PromptVersionDO> {

    /** 幂等插入：指纹冲突时什么都不改，保留最早的观测时间。 */
    int insertIgnore(PromptVersionDO record);

    /** 最近若干版本，观测时间倒序。 */
    List<PromptVersionDO> selectRecent(@Param("limit") int limit);
}
