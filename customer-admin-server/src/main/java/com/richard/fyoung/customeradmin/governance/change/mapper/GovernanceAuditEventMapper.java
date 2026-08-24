package com.richard.fyoung.customeradmin.governance.change.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernanceAuditEvent;

/** 治理审计只提供追加写与查询，不暴露更新/删除服务。 */
public interface GovernanceAuditEventMapper extends BaseMapper<AiGovernanceAuditEvent> {
}
