package com.richard.fyoung.customerwork.capability.semanticcache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.semanticcache.entity.SemanticCacheDO;
import com.richard.fyoung.customerwork.capability.semanticcache.entity.SemanticCacheScopeDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 语义缓存 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 *
 * <p>候选集查询、命中计数、LRU 淘汰三条都进 XML：它们各自带排序与限额语义，
 * 用 QueryWrapper 拼可读性差，且淘汰那条的子查询表达不出来。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SemanticCacheMapper extends BaseMapper<SemanticCacheDO> {

    /** 取候选集：同分区同意图、未过期，按最近命中倒序，限额。 */
    List<SemanticCacheDO> selectCandidates(@Param("scopeId") String scopeId,
                                           @Param("intent") String intent,
                                           @Param("notBeforeMs") long notBeforeMs,
                                           @Param("limit") int limit);

    /** 命中回写：计数 +1、刷新最近命中时间。 */
    int recordHit(@Param("id") Long id, @Param("hitAtMs") long hitAtMs);

    /** 分区内条目计数。 */
    long countByScope(@Param("scopeId") String scopeId);

    /**
     * 取 LRU 淘汰的时间界：按最近命中倒序数到第 {@code keepSize+1} 条的时间戳。
     *
     * <p>不用 {@code DELETE ... WHERE id NOT IN (SELECT ... LIMIT)} 一条搞定：
     * MySQL 不允许 DELETE 的子查询直接引用同表，绕开要多包一层派生表，而那条语句还得再过一遍
     * 租户拦截器的改写——低频操作没必要冒这个险，两次简单查询更稳。返回 null 表示没到淘汰量。</p>
     */
    Long selectEvictThreshold(@Param("scopeId") String scopeId, @Param("keepSize") int keepSize);

    /** 删除最近命中时间早于（含）界值的条目。 */
    int deleteOlderThan(@Param("scopeId") String scopeId, @Param("thresholdMs") long thresholdMs);

    /** 清空分区。 */
    int deleteByScope(@Param("scopeId") String scopeId);

    /** 运营视角列出条目，按命中次数降序（"哪些缓存真的在被复用"）。 */
    List<SemanticCacheDO> selectByHits(@Param("scopeId") String scopeId, @Param("limit") int limit);

    /** 列出分区及条目数，按条目数降序限额（看板的分区选择器）。 */
    List<SemanticCacheScopeDO> selectScopes(@Param("limit") int limit);
}
