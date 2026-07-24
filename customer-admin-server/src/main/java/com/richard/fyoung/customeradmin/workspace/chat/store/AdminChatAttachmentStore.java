package com.richard.fyoung.customeradmin.workspace.chat.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customerwork.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.attachment.AttachmentStore;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * admin 侧附件存储实现：把 starter 的 {@link ChatAttachment} 领域对象落到 admin 自有库表
 * {@code ai_chat_attachment}（不引 starter 的 {@code MybatisAttachmentStore}/持久层，admin 未加载 starter 持久层）。
 *
 * <h3>agent_code 落库方案</h3>
 * <p>starter 的 {@link ChatAttachment} 是渠道无关的通用领域对象，<b>不承载 agent_code</b>；而附件的落库动作发生在
 * {@code AttachmentParseService.parseAndStore(...)} <b>内部</b>（编排层直接调 {@code store.save}），admin 的 Service
 * 层拿不到"save 前"的切入点去补写。故这里用 {@link ThreadLocal} 承载 agent_code：Spring MVC 一次上传请求全程
 * 同一请求线程（非 WebFlux），Service 层在调 {@code parseAndStore} 前 {@link #bindAgentCode} 绑定、
 * {@code finally} 里 {@link #clearAgentCode} 清理，{@link #save} 读取写入 DO。这是"编入既有字段"之外最简单、
 * 不改 starter 契约的可维护方案（对比方案：save 后再 update 一次要多一次写且要额外持有 mapper）。</p>
 * @author owlzhangfq@gmail.com
 */
public class AdminChatAttachmentStore implements AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(AdminChatAttachmentStore.class);

    /** 当前请求线程绑定的 agent_code（save 时读取，Service 层负责 bind/clear，单请求单线程安全）。 */
    private static final ThreadLocal<String> AGENT_CODE = new ThreadLocal<>();

    private final AiChatAttachmentMapper attachmentMapper;

    public AdminChatAttachmentStore(AiChatAttachmentMapper attachmentMapper) {
        this.attachmentMapper = attachmentMapper;
    }

    /** 绑定当前请求线程的 agent_code（在调用 {@code parseAndStore} 之前）。 */
    public void bindAgentCode(String agentCode) {
        AGENT_CODE.set(agentCode);
    }

    /** 清理当前请求线程绑定的 agent_code（必须在 finally 中调用，防线程复用串号）。 */
    public void clearAgentCode() {
        AGENT_CODE.remove();
    }

    @Override
    public void save(ChatAttachment attachment) {
        if (attachment == null || attachment.getId() == null) {
            return;
        }
        try {
            attachmentMapper.insert(toEntity(attachment));
        } catch (Exception e) {
            log.error("attachment save failed, code={}, id={}", "ADMIN-ATTACHMENT-SAVE-FAIL", attachment.getId(), e);
            throw new IllegalStateException("failed to save attachment: " + attachment.getId(), e);
        }
    }

    @Override
    public Optional<ChatAttachment> findById(String id) {
        try {
            AiChatAttachment record = attachmentMapper.selectById(id);
            return record == null ? Optional.empty() : Optional.of(toDomain(record));
        } catch (Exception e) {
            log.error("attachment findById failed, code={}, id={}", "ADMIN-ATTACHMENT-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    /**
     * 按附件 ID + 智能体编码精确查一条（admin 私有：附件详情/下载链路的存在性 + 归属校验合一）。
     *
     * <p>把"附件不存在"与"跨 agent 访问"两种非法情形统一收敛成"查不到"（返回空），交由 Service 层
     * fast-fail 成同一个业务异常——领域对象 {@link ChatAttachment} 不承载 agent_code，故归属校验必须在
     * 持久层按列过滤完成，不回填领域对象。</p>
     */
    public Optional<ChatAttachment> findByIdAndAgentCode(String attachmentId, String agentCode) {
        try {
            QueryWrapper<AiChatAttachment> wrapper = new QueryWrapper<AiChatAttachment>()
                .eq("id", attachmentId).eq("agent_code", agentCode);
            AiChatAttachment record = attachmentMapper.selectOne(wrapper);
            return record == null ? Optional.empty() : Optional.of(toDomain(record));
        } catch (Exception e) {
            log.error("attachment findByIdAndAgentCode failed, code={}, id={}",
                "ADMIN-ATTACHMENT-FIND-FAIL", attachmentId, e);
            return Optional.empty();
        }
    }

    /**
     * 把一批附件绑定到某条用户消息（admin 私有：随消息发送时回填 session_id + message_id）。
     *
     * <p>只更新 {@code id IN (attachmentIds)} 且 {@code agent_code=agentCode} 的行（agent 归属兜底，
     * 防跨智能体误绑），返回实际更新行数供上层校验数量是否相符。持久化异常不在此吞——交上层
     * {@code catch(Exception)} 统一记录，不打断对话主流程。</p>
     */
    public int bindToMessage(String agentCode, String sessionId, String messageId, List<String> attachmentIds) {
        UpdateWrapper<AiChatAttachment> wrapper = new UpdateWrapper<AiChatAttachment>()
            .set("session_id", sessionId)
            .set("message_id", messageId)
            .eq("agent_code", agentCode)
            .in("id", attachmentIds);
        return attachmentMapper.update(null, wrapper);
    }

    @Override
    public List<ChatAttachment> listBySession(String sessionId) {
        try {
            QueryWrapper<AiChatAttachment> wrapper = new QueryWrapper<AiChatAttachment>()
                .eq("session_id", sessionId).orderByDesc("created_at");
            return attachmentMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("attachment listBySession failed, code={}, session={}",
                "ADMIN-ATTACHMENT-LIST-FAIL", sessionId, e);
            return List.of();
        }
    }

    /** 领域对象 → DO（枚举取 name()，agent_code 从线程上下文补写）。 */
    private AiChatAttachment toEntity(ChatAttachment a) {
        AiChatAttachment record = new AiChatAttachment();
        record.setId(a.getId());
        record.setSessionId(a.getSessionId());
        record.setMessageId(a.getMessageId());
        record.setAgentCode(AGENT_CODE.get() == null ? "" : AGENT_CODE.get());
        record.setUploader(a.getUploader());
        record.setChannel(a.getChannel());
        record.setFileName(a.getFileName());
        record.setExtension(a.getExtension());
        record.setMimeType(a.getMimeType());
        record.setFileSize(a.getFileSize());
        record.setStoragePath(a.getStoragePath());
        record.setParseStatus(a.getParseStatus() == null ? null : a.getParseStatus().name());
        record.setParsedText(a.getParsedText());
        record.setErrorMessage(a.getErrorMessage());
        record.setCreatedAt(a.getCreatedAt());
        return record;
    }

    /** DO → 领域对象（agent_code 是 admin 私有列，不回填领域对象）。 */
    private ChatAttachment toDomain(AiChatAttachment record) {
        return ChatAttachment.builder()
            .id(record.getId())
            .sessionId(record.getSessionId())
            .messageId(record.getMessageId())
            .uploader(record.getUploader())
            .channel(record.getChannel())
            .fileName(record.getFileName())
            .extension(record.getExtension())
            .mimeType(record.getMimeType())
            .fileSize(record.getFileSize() == null ? 0L : record.getFileSize())
            .storagePath(record.getStoragePath())
            .parseStatus(record.getParseStatus() == null ? null
                : AttachmentParseStatus.valueOf(record.getParseStatus()))
            .parsedText(record.getParsedText())
            .errorMessage(record.getErrorMessage())
            .createdAt(record.getCreatedAt())
            .build();
    }
}
