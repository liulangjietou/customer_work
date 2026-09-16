package com.richard.fyoung.customerwork.data.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.knowledge.KnowledgePublicSource;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
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
    default List<ChunkVectorDO> selectVectorsByPartitions(Long kbVersionId, Collection<Long> partitions,
                                                          Long afterId, int limit) {
        return selectVectorsByPartitionsForTenant(TenantContext.require(), kbVersionId, partitions, afterId, limit);
    }

    /** 显式租户条件不依赖可选的 MyBatis 租户插件。 */
    List<ChunkVectorDO> selectVectorsByPartitionsForTenant(@Param("tenantId") String tenantId,
                                                  @Param("kbVersionId") Long kbVersionId,
                                                  @Param("partitions") Collection<Long> partitions,
                                                  @Param("afterId") Long afterId,
                                                  @Param("limit") int limit);

    /** 命中确定后按 id 回查正文。 */
    default List<KnowledgeChunkDO> selectByIds(Collection<Long> ids) {
        return selectByIdsForTenant(TenantContext.require(), ids);
    }

    /** 正文回查独立限制当前租户，向量命中不构成授权。 */
    List<KnowledgeChunkDO> selectByIdsForTenant(@Param("tenantId") String tenantId,
                                               @Param("ids") Collection<Long> ids);

    /**
     * 取该版本下对终端用户开放的分区（文档修订 ID）。
     *
     * <p>C 端检索<b>只放行 PUBLIC</b>：终端用户不是内部员工，没有可用于细粒度 ACL 判定的身份，
     * 把非公开文档也放进检索范围等于让任何人都能问出内部资料。这条约束在 SQL 里落死，
     * 不依赖调用方记得过滤。</p>
     */
    default List<Long> selectPublicPartitions(Long kbVersionId) {
        return selectPublicPartitionsForTenant(TenantContext.require(), kbVersionId);
    }

    /** 公开范围限定于当前租户，PUBLIC 不代表所有租户共享。 */
    List<Long> selectPublicPartitionsForTenant(@Param("tenantId") String tenantId,
                                              @Param("kbVersionId") Long kbVersionId);

    /** 同一租户/版本/修订/分块保留主键，避免重复投影破坏历史消息引用；主键回填至 chunk。 */
    int upsertProjection(@Param("chunk") KnowledgeChunkDO chunk);

    /** 完整目标版本写入后清理多余分片，空集合代表该版本没有可保留的正文。 */
    int deleteVersionChunksExcept(@Param("versionId") Long versionId,
                                 @Param("retainedIds") Collection<Long> retainedIds);

    /** 按保存的完整引用读取已发布且公开的历史投影；列表不加载正文。 */
    KnowledgePublicSource findPublicSource(@Param("tenantId") String tenantId,
                                           @Param("knowledgeBaseId") String knowledgeBaseId,
                                           @Param("versionId") Long versionId,
                                           @Param("revisionId") Long revisionId,
                                           @Param("chunkId") Long chunkId,
                                           @Param("includeContent") boolean includeContent);
}
