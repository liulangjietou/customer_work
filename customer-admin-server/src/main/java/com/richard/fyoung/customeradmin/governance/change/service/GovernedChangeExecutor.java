package com.richard.fyoung.customeradmin.governance.change.service;

import com.richard.fyoung.customeradmin.governance.change.GovernedChangeType;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;

import java.util.Set;

/** 已通过双人复核后的类型化执行器。 */
public interface GovernedChangeExecutor {

    Set<GovernedChangeType> types();

    Object execute(AiGovernedChangeRequest request);
}
