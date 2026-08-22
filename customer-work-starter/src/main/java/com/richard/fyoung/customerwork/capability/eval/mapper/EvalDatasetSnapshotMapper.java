package com.richard.fyoung.customerwork.capability.eval.mapper;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalDatasetSnapshotDO;
import org.apache.ibatis.annotations.Param;

/** 数据集版本 Mapper：刻意不继承 BaseMapper，避免暴露更新/删除入口。 */
public interface EvalDatasetSnapshotMapper {

    int insertIgnore(EvalDatasetSnapshotDO row);

    EvalDatasetSnapshotDO selectByVersion(@Param("versionId") String versionId);

    EvalDatasetSnapshotDO selectByContent(@Param("evalType") String evalType,
                                          @Param("contentHash") String contentHash);
}
