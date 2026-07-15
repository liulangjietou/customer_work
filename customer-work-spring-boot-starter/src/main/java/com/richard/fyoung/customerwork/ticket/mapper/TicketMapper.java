package com.richard.fyoung.customerwork.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.ticket.entity.TicketDO;
import org.apache.ibatis.annotations.Param;

/**
 * 工单主表 Mapper。基础 CRUD 与分页走 {@link BaseMapper}；三条特殊语义（全字段 upsert、活跃工单定位、
 * 条件抢单）在 {@code TicketMapper.xml} 中手写。
 * @author owlzhangfq@gmail.com
 */
public interface TicketMapper extends BaseMapper<TicketDO> {

    /** 全字段 upsert（INSERT ... ON DUPLICATE KEY UPDATE，除 id/session_id/user_id/created_at_ms 外全部更新）。 */
    int upsert(TicketDO record);

    /** 查该会话下"非 CLOSED 且非 RESOLVED"的最新一张工单（按创建时间倒序取第一条）。 */
    TicketDO findActiveBySession(@Param("sessionId") String sessionId);

    /** 原子抢单：仅当当前为 WAITING_AGENT 时置 PROCESSING 并绑定坐席，返回受影响行数（0 表示已被抢走）。 */
    int claimAtomically(@Param("id") String id, @Param("agentId") String agentId, @Param("nowMs") long nowMs);
}
