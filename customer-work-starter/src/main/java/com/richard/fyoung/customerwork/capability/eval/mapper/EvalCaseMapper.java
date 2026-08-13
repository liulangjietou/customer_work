package com.richard.fyoung.customerwork.capability.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.eval.entity.EvalCaseDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测用例 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 *
 * <p>{@link #upsert} 表达按 {@code (tenant_id, eval_type, case_id)} 的
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}——同 ID 覆盖既是"修正用例"，也是"盖掉种子用例"。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EvalCaseMapper extends BaseMapper<EvalCaseDO> {

    /** 按业务唯一键 upsert。 */
    int upsert(EvalCaseDO record);

    /** 按类型取全部用例（含 disabled），用例编号正序。 */
    List<EvalCaseDO> selectByType(@Param("evalType") String evalType);

    /** 按类型与用例编号查一条。 */
    EvalCaseDO selectByCaseId(@Param("evalType") String evalType, @Param("caseId") String caseId);

    /** 按类型与用例编号删除。 */
    int deleteByCaseId(@Param("evalType") String evalType, @Param("caseId") String caseId);
}
