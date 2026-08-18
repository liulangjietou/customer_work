package com.richard.fyoung.customerwork.data.chatlog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.chatlog.entity.ChatMessageDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天消息 Mapper。追加走 {@link BaseMapper#insert}（AUTO 主键回填）；两条游标翻页查询
 * （会话维度 / 工单维度，均 {@code id DESC LIMIT}）在 {@code ChatMessageMapper.xml} 中手写。
 * @author owlzhangfq@gmail.com
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {

    /** 按业务消息号精确查询。 */
    ChatMessageDO findByMessageId(@Param("messageId") String messageId);

    /** 会话维度游标翻页：取 id 小于 beforeId（为空则不限）的最新 limit 条，id 倒序。 */
    List<ChatMessageDO> findBySessionPage(@Param("sessionId") String sessionId,
                                          @Param("beforeId") Long beforeId,
                                          @Param("limit") int limit);

    /** 工单维度游标翻页：语义同 {@link #findBySessionPage}。 */
    List<ChatMessageDO> findByTicketPage(@Param("ticketId") String ticketId,
                                         @Param("beforeId") Long beforeId,
                                         @Param("limit") int limit);
}
