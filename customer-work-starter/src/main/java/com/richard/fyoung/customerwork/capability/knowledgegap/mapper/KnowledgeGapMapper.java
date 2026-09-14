package com.richard.fyoung.customerwork.capability.knowledgegap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 知识盲区 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeGapMapper extends BaseMapper<KnowledgeGapDO> {

    /** 计数 upsert：同问题累加 miss_count 并刷新最近出现时间。 */
    int upsertMiss(KnowledgeGapDO record);

    /** 未命中排行（降序），即"最该优先补的知识"。 */
    List<KnowledgeGapDO> selectTopGaps(@Param("scopeId") String scopeId, @Param("limit") int limit);

    /** 复核工作清单，过滤在 LIMIT 前执行。 */
    List<KnowledgeGapDO> selectReviewedGaps(@Param("scopeId") String scopeId,
                                            @Param("limit") int limit, @Param("view") String view);

    /** 精确定位可信租户与分区，不依赖可选租户插件或数据库排序规则。 */
    @InterceptorIgnore(tenantLine = "1")
    KnowledgeGapDO selectByHash(@Param("tenantId") String tenantId, @Param("scopeId") String scopeId, @Param("questionHash") String questionHash);

    /** 复核版本比较更新；计数变化不构成编辑冲突。 */
    @InterceptorIgnore(tenantLine = "1")
    int updateClassification(@Param("tenantId") String tenantId, @Param("row") KnowledgeGapDO row, @Param("expectedRevision") long expectedRevision);

    /** 某分区全部盲区（统计用）。 */
    List<KnowledgeGapDO> selectByScope(@Param("scopeId") String scopeId);
}
