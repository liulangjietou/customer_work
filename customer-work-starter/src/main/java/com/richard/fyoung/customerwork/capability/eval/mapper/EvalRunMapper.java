package com.richard.fyoung.customerwork.capability.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.eval.entity.EvalRunDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测运行记录 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 *
 * <p>{@link #selectBaseline} 用 XML 表达"取某时刻之前最近一次同类型运行"——
 * 这是 {@code ORDER BY ... LIMIT 1} 的取最值语义，用 QueryWrapper 拼同样可行但可读性差，
 * 且这条 SQL 会随基线口径演进（如按提示词版本取基线），放 XML 便于集中演进。</p>
 * @author owlzhangfq@gmail.com
 */
public interface EvalRunMapper extends BaseMapper<EvalRunDO> {

    /** 按类型取最近若干次运行，按写入顺序倒序。 */
    List<EvalRunDO> selectRecent(@Param("evalType") String evalType, @Param("limit") int limit);

    /** 取 {@code runId} 之前最近的一次同类型运行（对比基线）；无则返回 null。 */
    EvalRunDO selectBaseline(@Param("evalType") String evalType, @Param("runId") String runId);
}
