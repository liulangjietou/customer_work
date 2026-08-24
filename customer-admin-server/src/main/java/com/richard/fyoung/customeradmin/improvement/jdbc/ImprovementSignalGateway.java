package com.richard.fyoung.customeradmin.improvement.jdbc;

import com.richard.fyoung.customeradmin.improvement.mapper.ImprovementSignalMapper;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;

/** 改进闭环在客服库上的只读信号与评测用例门面。 */
public record ImprovementSignalGateway(
    ImprovementSignalMapper signalMapper,
    EvalCaseStore evalCaseStore
) {
}
