package com.richard.fyoung.customerwork.capability.eval;

import java.util.Optional;

/** 评测数据集快照存储 SPI：只允许按内容幂等创建与读取，不暴露更新、删除。 */
public interface EvalDatasetSnapshotStore {

    /** 同租户、同类型、同内容返回已有版本；内容变化才创建新版本。 */
    EvalDatasetSnapshot saveIfAbsent(EvalDatasetSnapshot snapshot);

    Optional<EvalDatasetSnapshot> find(String versionId);
}
