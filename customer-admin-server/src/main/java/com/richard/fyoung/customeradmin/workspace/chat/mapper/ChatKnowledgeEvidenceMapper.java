package com.richard.fyoung.customeradmin.workspace.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.chat.entity.ChatKnowledgeEvidence;
import org.apache.ibatis.annotations.Param;

/** 条件显式包含租户与框架主体分区，不能靠消息 ID 单独定位。 */
public interface ChatKnowledgeEvidenceMapper extends BaseMapper<ChatKnowledgeEvidence> {
    int insertIfAbsent(@Param("evidence") ChatKnowledgeEvidence evidence);

    ChatKnowledgeEvidence findByMessage(@Param("tenantId") String tenantId,
                                        @Param("stateUserId") String stateUserId,
                                        @Param("agentCode") String agentCode,
                                        @Param("sessionId") String sessionId,
                                        @Param("messageId") String messageId);
}
