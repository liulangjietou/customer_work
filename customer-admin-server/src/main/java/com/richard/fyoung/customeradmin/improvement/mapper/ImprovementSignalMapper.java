package com.richard.fyoung.customeradmin.improvement.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSourceFact;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 客服库原始信号与发布 revision 曝光事实查询；SQL 显式携带 tenant_id。 */
public interface ImprovementSignalMapper {

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT question_hash AS source_key, question, question_hash AS signal_hash,
               miss_count AS signal_count, NULL AS eval_case_id
        FROM cw_knowledge_gap
        WHERE tenant_id = #{tenantId} AND question_hash = #{sourceKey}
        LIMIT 1
        """)
    ImprovementSourceFact findKnowledgeGap(@Param("tenantId") String tenantId,
                                            @Param("sourceKey") String sourceKey);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT id AS source_key, user_input AS question, signal_hash,
               (SELECT COUNT(*) FROM cw_badcase matched
                 WHERE matched.tenant_id = source.tenant_id
                   AND matched.signal_hash = source.signal_hash) AS signal_count,
               adopted_eval_case_id AS eval_case_id
        FROM cw_badcase source
        WHERE source.tenant_id = #{tenantId} AND source.id = #{sourceKey}
        LIMIT 1
        """)
    ImprovementSourceFact findBadcase(@Param("tenantId") String tenantId,
                                      @Param("sourceKey") String sourceKey);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT COALESCE(MAX(miss_count), 0)
        FROM cw_knowledge_gap
        WHERE tenant_id = #{tenantId} AND question_hash = #{signalHash}
        """)
    long knowledgeGapSignalCount(@Param("tenantId") String tenantId,
                                 @Param("signalHash") String signalHash);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT COUNT(*) FROM cw_badcase
        WHERE tenant_id = #{tenantId} AND signal_hash = #{signalHash}
        """)
    long badcaseSignalCount(@Param("tenantId") String tenantId,
                            @Param("signalHash") String signalHash);

    @InterceptorIgnore(tenantLine = "1")
    @Select("""
        SELECT COUNT(*) FROM cw_agent_call_log
        WHERE tenant_id = #{tenantId} AND runtime_revision = #{revision}
          AND start_time >= #{startMs} AND start_time < #{endMs}
        """)
    long exposureCalls(@Param("tenantId") String tenantId,
                       @Param("revision") String revision,
                       @Param("startMs") long startMs,
                       @Param("endMs") long endMs);
}
