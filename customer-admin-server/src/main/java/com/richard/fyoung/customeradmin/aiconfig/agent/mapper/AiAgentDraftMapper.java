package com.richard.fyoung.customeradmin.aiconfig.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentDraft;

/** 草稿读写均由服务叠加归属人条件，由租户拦截器叠加当前租户条件。 */
public interface AiAgentDraftMapper extends BaseMapper<AiAgentDraft> {
}
