package com.richard.fyoung.customeradmin.workspace.chat.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话状态表 {@code ai_chat_session_state}（框架 {@code MysqlAgentStateStore} 自建、无 DO）的只读分页查询。
 *
 * <p>不迁移、不建表、不写 DO——仅对既有框架表做轻量只读查询：按 {@code session_id} 前缀
 * （{@code {agentCode}:%}，命中主键最左前缀）分页取出本页会话 id，SQL 侧完成分页与排序，
 * 上层只对本页 20 个会话逐个 resolve 整段 {@code AgentState}，避免把该智能体全部会话的 longtext
 * blob 都拉进内存。SQL 写在 {@code resources/mapper/ChatSessionStateQueryMapper.xml}。
 * 由 {@code CustomerAdminServerApplication} 的 {@code @MapperScan("...**.mapper")} 自动扫描。</p>
 * @author owlzhangfq@gmail.com
 */
public interface ChatSessionStateQueryMapper {

    /**
     * 分页取某智能体的会话 id（完整带 {@code {agentCode}:} 前缀），按最后更新时间倒序。
     *
     * <p>一个会话在表里对应多行（不同 {@code state_key}/{@code item_index}），故 {@code GROUP BY session_id}
     * 后用 {@code MAX(updated_at)} 作为该会话的最后活跃时间排序——不硬编码框架内部的 {@code state_key} 取值，
     * 对多行结构保持稳健。</p>
     *
     * @param agentCode 智能体编码（会话 id 前缀，SQL 内以 {@code CONCAT(#{agentCode}, ':%')} 拼 LIKE，防注入）
     * @param offset    偏移量（{@code (page-1)*size}）
     * @param limit     每页条数
     */
    /*
     * ownerUserId 传 null 表示当前数据范围放宽到本租户及以上，不按人过滤；
     * 两个方法必须传同一个值，否则总数与数据页口径不一致会翻出空页。
     */
    List<String> pageSessionIds(@Param("tenantId") String tenantId,
                                @Param("stateUserId") String stateUserId,
                                @Param("agentCode") String agentCode,
                                @Param("ownerUserId") Long ownerUserId,
                                @Param("offset") long offset,
                                @Param("limit") long limit);

    /** 某智能体的去重会话总数（{@code COUNT(DISTINCT session_id)}），供分页总数。 */
    long countSessions(@Param("tenantId") String tenantId,
                       @Param("stateUserId") String stateUserId,
                       @Param("agentCode") String agentCode,
                       @Param("ownerUserId") Long ownerUserId);
}
