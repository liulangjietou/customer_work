package com.richard.fyoung.customerwork.capability.deadletter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.capability.deadletter.entity.DeadLetterDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 死信 Mapper：继承 {@link BaseMapper} 复用单表 CRUD。
 * @author owlzhangfq@gmail.com
 */
public interface DeadLetterMapper extends BaseMapper<DeadLetterDO> {

    /** 按主键 upsert：新建与每次重投后的回写共用。 */
    int upsert(DeadLetterDO record);

    /** 取到期可重投的（PENDING 且 next_retry_at_ms <= now），按到期时间正序。 */
    List<DeadLetterDO> selectDue(@Param("nowMs") long nowMs, @Param("limit") int limit);

    /** 按状态查询，创建时间倒序。 */
    List<DeadLetterDO> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    /** 按状态计数。 */
    long countByStatus(@Param("status") String status);
}
