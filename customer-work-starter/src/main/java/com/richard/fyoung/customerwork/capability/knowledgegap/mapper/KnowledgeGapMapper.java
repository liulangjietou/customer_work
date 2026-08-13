package com.richard.fyoung.customerwork.capability.knowledgegap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识盲区 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeGapMapper extends BaseMapper<KnowledgeGapDO> {

    /** 计数 upsert：同问题累加 miss_count 并刷新最近出现时间。 */
    int upsertMiss(KnowledgeGapDO record);

    /** 未命中排行（降序），即"最该优先补的知识"。 */
    List<KnowledgeGapDO> selectTopGaps(@Param("scopeId") String scopeId, @Param("limit") int limit);

    /** 某分区全部盲区（统计用）。 */
    List<KnowledgeGapDO> selectByScope(@Param("scopeId") String scopeId);
}
