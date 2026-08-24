package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageAttachmentVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessageVO;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatSessionSummary;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.ChatSessionStateQueryMapper;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentStateAccessor;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemoryScope;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.data.attachment.AttachmentStore;
import com.richard.fyoung.customerwork.data.attachment.ChatAttachment;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 历史会话查询：会话列表（SQL 级分页）+ 重新打开某次历史会话的完整消息。
 *
 * <p>不新增数据库表——直接读框架自建的会话状态表（批次六起已换成
 * {@link io.agentscope.extensions.mysql.state.MysqlAgentStateStore}，重启不丢）。历史列表按
 * {@link ChatSessionStateQueryMapper} 在 SQL 侧完成分页与排序（{@code session_id LIKE '{agentCode}:%'}
 * 走主键最左前缀），每页只对本页会话逐个 {@link AgentStateAccessor#resolve} 取完整 {@link AgentState}
 * 组装摘要——早期实现枚举该智能体全部 sessionId、逐个 resolve 整段 longtext blob 只为算预览/消息数，
 * 会话数一多就把全部 blob 拉进内存，故改成分页。chat 与 vibecoding 共用同一套 session 状态
 * （只是 sessionId 不同），历史列表天然把两者混在一起，不做类型区分——如实符合"同一智能体下的历史
 * 对话"这个需求本身的粒度，不做额外的会话类型元数据设计。</p>
 *
 * <p>分页列表不走缓存（每页仅 20 个 blob，成本可控，不值得为它引入分页缓存复杂度）；
 * {@link ChatHistoryCache} 只缓存单次历史会话的消息内容。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatHistoryService {

    private static final int PREVIEW_MAX_LENGTH = 60;
    /** 默认每页条数（与 {@code ChatController#sessions} 的 {@code size} 默认值保持一致）。 */
    public static final long DEFAULT_PAGE_SIZE = 20;

    private final AgentInstanceCache agentInstanceCache;
    private final AgentStateStore agentStateStore;
    private final AgentStateAccessor agentStateAccessor;
    private final ChatHistoryCache historyCache;
    private final ChatSessionStateQueryMapper sessionStateQueryMapper;
    /** 容器里注入的是 admin 自有的 {@code AdminChatAttachmentStore}（落 ai_chat_attachment），见 AdminAttachmentConfig。 */
    private final AttachmentStore attachmentStore;
    private final AdminTenantProperties tenantProperties;

    public ChatHistoryService(AgentInstanceCache agentInstanceCache, AgentStateStore agentStateStore,
                               AgentStateAccessor agentStateAccessor, ChatHistoryCache historyCache,
                               ChatSessionStateQueryMapper sessionStateQueryMapper,
                               AttachmentStore attachmentStore, AdminTenantProperties tenantProperties) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentStateStore = agentStateStore;
        this.agentStateAccessor = agentStateAccessor;
        this.historyCache = historyCache;
        this.sessionStateQueryMapper = sessionStateQueryMapper;
        this.attachmentStore = attachmentStore;
        this.tenantProperties = tenantProperties;
    }

    /**
     * 历史会话列表（按最后更新时间倒序、SQL 级分页）。mapper 只查出本页会话 id（带
     * {@code {agentCode}:} 前缀），去前缀后逐个 resolve 完整上下文组装摘要——context 为空的会话跳过，
     * 因此本页返回条数可能略少于 {@code size}（顺序仍以 SQL 的 updated_at 倒序为准）。
     *
     * @param page 页码（从 1 起）
     * @param size 每页条数
     */
    public PageResult<ChatSessionSummary> listSessions(String agentCode, Long ownerUserId, long page, long size) {
        // 只有明确处于本租户及以上范围时才不按人过滤，列出本租户内已认领的全部会话；
        // 缺上下文时保持按人过滤（放宽必须有明确依据）。归一在这一处完成，
        // 总数与数据页因此必然用同一个值，不会出现翻页空页
        ownerUserId = DataScopeContext.relaxedBeyondSelf() ? null : ownerUserId;
        String tenantId = tenantProperties.isEnabled() ? TenantContext.require() : TenantContext.DEFAULT;
        AgentMemoryScope memoryScope = AgentMemoryScope.current(agentCode);
        String stateUserId = memoryScope.stateUserId();
        long total = sessionStateQueryMapper.countSessions(tenantId, stateUserId, agentCode, ownerUserId);
        List<ChatSessionSummary> summaries = new ArrayList<>();

        PageResult<ChatSessionSummary> result = new PageResult<>();
        result.setPageNum(page);
        result.setPageSize(size);
        result.setTotal(total);
        result.setList(summaries);
        if (total == 0) {
            return result;
        }

        long offset = (page - 1) * size;
        List<String> prefixedSessionIds = sessionStateQueryMapper.pageSessionIds(
            tenantId, stateUserId, agentCode, ownerUserId, offset, size);
        if (CollectionUtils.isEmpty(prefixedSessionIds)) {
            return result;
        }

        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        String prefix = stateUserId + ":";
        for (String prefixedSessionId : prefixedSessionIds) {
            // mapper 查出的是完整带前缀 id，resolve 用的是去前缀的裸 sessionId（与写入侧 listSessionIds 语义对齐）
            String sessionId = prefixedSessionId.startsWith(prefix)
                ? prefixedSessionId.substring(prefix.length()) : prefixedSessionId;
            List<Msg> context = agentStateAccessor.resolve(agent, stateUserId, sessionId).getContext();
            if (context.isEmpty()) {
                continue;
            }
            summaries.add(new ChatSessionSummary(sessionId, previewOf(context),
                context.get(context.size() - 1).getTimestamp(), context.size()));
        }
        return result;
    }

    /** 30 分钟读缓存命中直接返回；未命中回源 MySQL（{@link AgentStateStore}）后回填缓存。 */
    public List<ChatMessageVO> getMessages(String agentCode, String sessionId) {
        AgentMemoryScope memoryScope = AgentMemoryScope.current(agentCode);
        Optional<List<ChatMessageVO>> cached = historyCache.getMessages(memoryScope, sessionId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        AgentState state = agentStateAccessor.resolve(agent, memoryScope.stateUserId(), sessionId);
        // 该会话的附件按绑定的 message_id 分组（未绑定的跳过）：查询失败 listBySession 已内置兜底返回空列表，
        // 不影响历史返回。附件通常按用户消息挂载，一条消息可带多个附件。
        Map<String, List<ChatMessageAttachmentVO>> attachmentsByMessage = attachmentStore.listBySession(sessionId).stream()
            .filter(a -> StringUtils.hasText(a.getMessageId()))
            .collect(Collectors.groupingBy(ChatAttachment::getMessageId,
                Collectors.mapping(this::toAttachmentVO, Collectors.toList())));

        List<ChatMessageVO> messages = new ArrayList<>();
        for (Msg msg : state.getContext()) {
            if (msg.getRole() != MsgRole.USER && msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = msg.getTextContent();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            // id=框架 Msg.id：附件按 message_id 挂回对应消息；无附件时给空列表（契约要求非 null）
            List<ChatMessageAttachmentVO> msgAttachments = attachmentsByMessage.getOrDefault(msg.getId(), List.of());
            messages.add(new ChatMessageVO(msg.getId(),
                msg.getRole() == MsgRole.USER ? "user" : "assistant", text, msg.getTimestamp(), msgAttachments));
        }
        historyCache.putMessages(memoryScope, sessionId, messages);
        return messages;
    }

    /** 领域附件 → 历史消息附件摘要 VO（解析状态取枚举名，不内联解析文本）。 */
    private ChatMessageAttachmentVO toAttachmentVO(ChatAttachment a) {
        return new ChatMessageAttachmentVO(a.getId(), a.getFileName(), a.getMimeType(), a.getFileSize(),
            a.getParseStatus() == null ? null : a.getParseStatus().name());
    }

    /**
     * 单条会话摘要：给定 agentCode+sessionId 直接解析，不走"列出全部再过滤"——Projects 详情页按需查
     * 单条会话时用（一个项目里的会话可能横跨很多智能体，没必要把每个智能体的全部会话都拉一遍）。
     * 会话已查不到内容（比如底层状态被清理）时返回空。
     */
    public Optional<ChatSessionSummary> getSessionSummary(String agentCode, String sessionId) {
        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        List<Msg> context = agentStateAccessor.resolve(
            agent, AgentMemoryScope.current(agentCode).stateUserId(), sessionId).getContext();
        if (context.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ChatSessionSummary(sessionId, previewOf(context),
            context.get(context.size() - 1).getTimestamp(), context.size()));
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
