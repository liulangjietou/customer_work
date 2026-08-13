package com.richard.fyoung.customerwork.core.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.core.memory.entity.LongTermMemoryDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆事实 Mapper：继承 {@link BaseMapper} 复用单表 CRUD（清空 / 计数走 LambdaQueryWrapper），
 * 去重写入与召回候选集扫描两条复杂 SQL 走 XML。
 * @author owlzhangfq@gmail.com
 */
public interface LongTermMemoryMapper extends BaseMapper<LongTermMemoryDO> {

    /** 去重写入：命中 {@code uk_ltm_scope_fact} 时不新增行（同分区同内容只留一条）。 */
    int insertIfAbsent(LongTermMemoryDO record);

    /**
     * 取某分区最近写入的若干条事实（召回候选集）。
     *
     * <p>召回打分在 Java 侧做（字符重合度，SQL 表达不了），故先按 {@code id} 倒序取一个有上限的候选集
     * 再打分——不设上限的话，长期积累的分区会把整表拉进内存。</p>
     */
    List<String> selectRecentFacts(@Param("scopeId") String scopeId, @Param("limit") int limit);
}
