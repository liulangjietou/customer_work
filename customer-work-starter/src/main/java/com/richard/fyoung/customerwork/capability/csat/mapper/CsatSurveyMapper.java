package com.richard.fyoung.customerwork.capability.csat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.csat.entity.CsatSurveyDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * CSAT 调查 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 * @author owlzhangfq@gmail.com
 */
public interface CsatSurveyMapper extends BaseMapper<CsatSurveyDO> {

    /** 按会话 upsert：邀请与评分共用一个写入口。 */
    int upsert(CsatSurveyDO record);

    /** 按分区与时间窗查（窗口以邀请时间为准）。 */
    List<CsatSurveyDO> selectByWindow(@Param("scopeId") String scopeId,
                                      @Param("startMs") long startMs,
                                      @Param("endMs") long endMs);
}
