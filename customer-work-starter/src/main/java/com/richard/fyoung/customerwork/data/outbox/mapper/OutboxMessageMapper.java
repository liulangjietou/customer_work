package com.richard.fyoung.customerwork.data.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.outbox.entity.OutboxMessageDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Outbox Mapper：复杂租约 SQL 集中在 XML。 */
public interface OutboxMessageMapper extends BaseMapper<OutboxMessageDO> {

    int insertMessage(OutboxMessageDO message);

    List<String> selectClaimCandidates(@Param("nowMs") long nowMs, @Param("limit") int limit);

    int claim(@Param("id") String id, @Param("owner") String owner,
              @Param("nowMs") long nowMs, @Param("leaseUntilMs") long leaseUntilMs);

    List<OutboxMessageDO> selectClaimed(@Param("owner") String owner,
                                        @Param("leaseUntilMs") long leaseUntilMs);

    int complete(@Param("record") OutboxMessageDO record, @Param("owner") String owner);

    long countByStatus(@Param("status") String status);
}
