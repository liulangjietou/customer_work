package com.richard.fyoung.customerwork.data.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.ChunkVectorDO;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 受管知识库分片 Mapper。
 *
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

    /**
     * 按分区拉取窄投影（不含正文），供向量打分。
     *
     * <p>分批由调用方控制：{@code afterId} 传上一批的最大 id，配合 {@code limit} 顺序推进。
     * 一次性全拉是这条链路原本的病根。</p>
     */
    List<ChunkVectorDO> selectVectorsByPartitions(@Param("kbVersionId") Long kbVersionId,
                                                  @Param("partitions") Collection<Long> partitions,
                                                  @Param("afterId") Long afterId,
                                                  @Param("limit") int limit);

    /** 命中确定后按 id 回查正文。 */
    List<KnowledgeChunkDO> selectByIds(@Param("ids") Collection<Long> ids);

    /** 删除某个知识库版本的全部分片（重新投影前清场）。 */
    int deleteByVersion(@Param("kbVersionId") Long kbVersionId);
}
