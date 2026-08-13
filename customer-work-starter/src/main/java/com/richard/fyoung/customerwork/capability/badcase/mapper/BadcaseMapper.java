package com.richard.fyoung.customerwork.capability.badcase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.badcase.entity.BadcaseDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * badcase Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 *
 * <p>查询与计数走 XML 的动态 WHERE（条件可空即不限），不用 QueryWrapper 拼——
 * 筛选维度还会长，集中在 XML 里演进比散在代码里可读。</p>
 * @author owlzhangfq@gmail.com
 */
public interface BadcaseMapper extends BaseMapper<BadcaseDO> {

    /** 按主键 upsert：新建与状态流转回写共用。 */
    int upsert(BadcaseDO record);

    /** 条件查询，时间倒序。 */
    List<BadcaseDO> selectByCondition(@Param("status") String status,
                                      @Param("source") String source,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    /** 条件计数。 */
    long countByCondition(@Param("status") String status, @Param("source") String source);
}
