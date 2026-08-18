package com.richard.fyoung.customerwork.safety.subjectquota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitRank;
import com.richard.fyoung.customerwork.safety.subjectquota.entity.SubjectQuotaHitDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 超限命中 Mapper：写入与明细查询走 {@link BaseMapper}，排行是聚合、进 XML。
 * @author owlzhangfq@gmail.com
 */
public interface SubjectQuotaHitMapper extends BaseMapper<SubjectQuotaHitDO> {

    /** 命中排行：按主体分组计数，命中次数倒序。 */
    List<SubjectQuotaHitRank> selectRank(@Param("tenantId") String tenantId,
                                         @Param("sinceMs") long sinceMs,
                                         @Param("limit") int limit);
}
