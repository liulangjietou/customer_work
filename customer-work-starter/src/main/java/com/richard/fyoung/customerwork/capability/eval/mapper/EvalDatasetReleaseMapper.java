package com.richard.fyoung.customerwork.capability.eval.mapper;

import com.richard.fyoung.customerwork.capability.eval.entity.EvalDatasetReleaseDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 命名版本 Mapper；不继承通用 CRUD，避免误暴露内容更新与删除。 */
public interface EvalDatasetReleaseMapper {

    int insert(EvalDatasetReleaseDO row);

    EvalDatasetReleaseDO selectByReleaseId(@Param("releaseId") String releaseId);

    List<EvalDatasetReleaseDO> selectByType(@Param("evalType") String evalType);

    int review(@Param("releaseId") String releaseId,
               @Param("targetStatus") String targetStatus,
               @Param("reviewComment") String reviewComment,
               @Param("reviewedBy") Long reviewedBy,
               @Param("reviewedAtMs") long reviewedAtMs);
}
