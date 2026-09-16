package com.richard.fyoung.customerwork.data.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.knowledge.entity.KnowledgeVersionDO;
import org.apache.ibatis.annotations.Param;

/**
 * 受管知识库版本投影 Mapper。
 *
 * @author owlzhangfq@gmail.com
 */
public interface KnowledgeVersionMapper extends BaseMapper<KnowledgeVersionDO> {

    /** Admin 持有知识库行锁后同步封锁本租户该知识库的全部旧投影。 */
    int blockByKnowledgeBase(@Param("knowledgeBaseId") String knowledgeBaseId,
                             @Param("updatedAtMs") long updatedAtMs);

    /** 重新投影开始前封锁目标版本；未完成的分片集合不能对客户可见。 */
    int blockByVersion(@Param("versionId") Long versionId, @Param("updatedAtMs") long updatedAtMs);
}
