package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity.EvalGatePolicyEntity;

/** 租户门禁策略 Mapper。 */
public interface EvalGatePolicyMapper extends BaseMapper<EvalGatePolicyEntity> {

    int upsert(EvalGatePolicyEntity policy);
}
