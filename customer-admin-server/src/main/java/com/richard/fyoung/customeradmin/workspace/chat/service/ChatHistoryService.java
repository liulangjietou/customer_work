package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentStateAccessor;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 历史会话查询：会话列表 + 重新打开某次历史会话的完整消息。
 *
 * <p>不新增数据库表——直接读 {@link AgentStateStore}（批次六起已换成
 * {@link io.agentscope.extensions.mysql.state.MysqlAgentStateStore}，重启不丢）：
 * {@link AgentStateStore#listSessionIds} 枚举某智能体下的全部 sessionId，
 * {@link AgentStateAccessor#resolve} 取每个 sessionId 的完整 {@link AgentState}，
 * {@link AgentState#getContext()} 即完整对话消息列表。chat 与 vibecoding 共用同一套 session 状态
 * （只是 sessionId 不同），历史列表天然把两者混在一起，不做类型区分——如实符合"同一智能体下的历史
 * 对话"这个需求本身的粒度，不做额外的会话类型元数据设计。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatHistoryService {

    private static final int PREVIEW_MAX_LENGTH = 60;

    private final AgentInstanceCache agentInstanceCache;
    private final AgentStateStore agentStateStore;
    private final AgentStateAccessor agentStateAccessor;
    private final ChatHistoryCache historyCache;

    public ChatHistoryService(AgentInstanceCache agentInstanceCache, AgentStateStore agentStateStore,
                               AgentStateAccessor agentStateAccessor, ChatHistoryCache historyCache) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentStateStore = agentStateStore;
        this.agentStateAccessor = agentStateAccessor;
        this.historyCache = historyCache;
    }

    /** 30 分钟读缓存命中直接返回；未命中回源 MySQL（{@link AgentStateStore}）后回填缓存。 */
    public List<ChatSessionSummary> listSessions(String agentCode) {
        Optional<List<ChatSessionSummary>> cached = historyCache.getSessions(agentCode);
        if (cached.isPresent()) {
            return cached.get();
        }

        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        List<ChatSessionSummary> summaries = new ArrayList<>();
        for (String sessionId : agentStateStore.listSessionIds(agentCode)) {
            List<Msg> context = agentStateAccessor.resolve(agent, agentCode, sessionId).getContext();
            if (context.isEmpty()) {
                continue;
            }
            summaries.add(new ChatSessionSummary(sessionId, previewOf(context), context.get(context.size() - 1).getTimestamp(),
                context.size()));
        }
        summaries.sort(Comparator.comparing(ChatSessionSummary::lastMessageTime, Comparator.nullsLast(Comparator.reverseOrder())));
        historyCache.putSessions(agentCode, summaries);
        return summaries;
    }

    /** 30 分钟读缓存命中直接返回；未命中回源 MySQL（{@link AgentStateStore}）后回填缓存。 */
    public List<ChatMessageVO> getMessages(String agentCode, String sessionId) {
        Optional<List<ChatMessageVO>> cached = historyCache.getMessages(agentCode, sessionId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        AgentState state = agentStateAccessor.resolve(agent, agentCode, sessionId);
        List<ChatMessageVO> messages = new ArrayList<>();
        for (Msg msg : state.getContext()) {
            if (msg.getRole() != MsgRole.USER && msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            messages.add(new ChatMessageVO(msg.getRole() == MsgRole.USER ? "user" : "assistant", text, msg.getTimestamp()));
        }
        historyCache.putMessages(agentCode, sessionId, messages);
        return messages;
    }

    private String previewOf(List<Msg> context) {
        for (Msg msg : context) {
            if (msg.getRole() == MsgRole.USER && StringUtils.hasText(msg.getTextContent())) {
                String text = msg.getTextContent();
                return text.length() > PREVIEW_MAX_LENGTH ? text.substring(0, PREVIEW_MAX_LENGTH) + "..." : text;
            }
        }
        return "";
    }
}
