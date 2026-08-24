package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReleaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;

/** 客服端库评测聚合门面：运行事实与数据集治理共用一套连接和租户插件。 */
public record EvalGateway(
    EvalRunStore runStore,
    EvalCaseStore caseStore,
    EvalDatasetSnapshotStore snapshotStore,
    EvalDatasetReleaseStore releaseStore
) {
}
