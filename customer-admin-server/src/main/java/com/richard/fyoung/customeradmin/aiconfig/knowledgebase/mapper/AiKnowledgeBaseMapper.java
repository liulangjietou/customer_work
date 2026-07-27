package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * RAG 知识库配置 Mapper。
 *
 * <p>下面两个方法专门绕开 MyBatis-Plus 的逻辑删除过滤：{@code ai_knowledge_base.uk_ai_kb_name} 是
 * <b>不含 deleted 列</b>的纯数据库唯一约束，被软删除的行仍然占着名字，而 {@code LambdaQueryWrapper}
 * 会自动追加 {@code AND deleted=0} 从而查不到它——必须用原生 SQL 才能看见/复活。手法与
 * {@code SysUserMapper#selectByUsernameIgnoreLogicDelete}/{@code reviveDeletedUser} 完全一致。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AiKnowledgeBaseMapper extends BaseMapper<AiKnowledgeBase> {

    /**
     * 按名称查一条<b>已被软删除</b>的知识库行（{@code deleted=1}）。用原生 {@code @Select}，
     * 不会被自动追加 {@code AND deleted=0}，因此能看见被逻辑删除、但仍占着唯一索引的那一行。
     */
    @Select("SELECT * FROM ai_knowledge_base WHERE kb_name = #{kbName} AND deleted = 1 LIMIT 1")
    AiKnowledgeBase selectDeletedByName(@Param("kbName") String kbName);

    /**
     * "复活"一个被软删除的知识库行：只把 {@code deleted} 置回 0，其余业务字段由调用方随后用
     * {@code updateById} 按新的保存请求整体覆盖（复活后该行对 MyBatis-Plus 重新可见）。
     * 同样用原生 {@code @Update} 才能命中当前 {@code deleted=1} 的目标行。
     */
    @Update("UPDATE ai_knowledge_base SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int reviveDeleted(@Param("id") Long id);
}
