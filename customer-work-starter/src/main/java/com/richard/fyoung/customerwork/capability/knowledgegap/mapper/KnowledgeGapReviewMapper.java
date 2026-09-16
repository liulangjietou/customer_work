package com.richard.fyoung.customerwork.capability.knowledgegap.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.knowledgegap.entity.KnowledgeGapReviewDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 复核记录与原始问题使用同一租户、分区和哈希定位。 */
public interface KnowledgeGapReviewMapper extends BaseMapper<KnowledgeGapReviewDO> {
    /** 每页返回 20 条更早记录，调用方可用最小修订号继续读取。 */
    @Select("""
        SELECT * FROM cw_knowledge_gap_review
        WHERE scope_id = #{scopeId} AND question_hash = #{questionHash} AND revision < #{beforeRevision}
        ORDER BY revision DESC LIMIT 20
        """)
    List<KnowledgeGapReviewDO> history(@Param("scopeId") String scopeId,
                                      @Param("questionHash") String questionHash,
                                      @Param("beforeRevision") long beforeRevision);
}
