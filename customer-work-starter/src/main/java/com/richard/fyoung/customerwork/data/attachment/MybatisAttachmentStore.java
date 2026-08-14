package com.richard.fyoung.customerwork.data.attachment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.data.attachment.entity.ChatAttachmentDO;
import com.richard.fyoung.customerwork.data.attachment.mapper.ChatAttachmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 附件存储（生产参考实现：{@code customer-work.attachment.store-mode=jdbc} 时启用）。
 *
 * <p>把附件记录结构化落 {@code cw_chat_attachment}，保证重启 / 多实例部署下附件可追溯。持久层异常统一
 * {@code catch(Exception)} 兜底（HikariPool / MyBatis 初始化异常均为 RuntimeException）：查询失败返回空、
 * 保存失败抛 {@link IllegalStateException} 交编排层记录。建表由统一 Flyway 迁移负责，本类不建表。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAttachmentStore implements AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisAttachmentStore.class);

    private final ChatAttachmentMapper attachmentMapper;

    public MybatisAttachmentStore(ChatAttachmentMapper attachmentMapper) {
        this.attachmentMapper = attachmentMapper;
    }

    @Override
    public void save(ChatAttachment attachment) {
        if (attachment == null || attachment.getId() == null) {
            return;
        }
        try {
            attachmentMapper.insert(toDO(attachment));
        } catch (Exception e) {
            log.error("attachment save failed, code={}, id={}", "ATTACHMENT-STORE-SAVE-FAIL", attachment.getId(), e);
            throw new IllegalStateException("failed to save attachment: " + attachment.getId(), e);
        }
    }

    @Override
    public Optional<ChatAttachment> findById(String id) {
        try {
            ChatAttachmentDO record = attachmentMapper.selectById(id);
            return record == null ? Optional.empty() : Optional.of(toDomain(record));
        } catch (Exception e) {
            log.error("attachment findById failed, code={}, id={}", "ATTACHMENT-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ChatAttachment> listBySession(String sessionId) {
        try {
            QueryWrapper<ChatAttachmentDO> wrapper = new QueryWrapper<ChatAttachmentDO>()
                .eq("session_id", sessionId).orderByDesc("created_at");
            return attachmentMapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("attachment listBySession failed, code={}, session={}",
                "ATTACHMENT-STORE-LIST-FAIL", sessionId, e);
            return List.of();
        }
    }

    /** 领域对象 → DO（枚举取 name()）。 */
    private ChatAttachmentDO toDO(ChatAttachment a) {
        ChatAttachmentDO record = new ChatAttachmentDO();
        record.setId(a.getId());
        record.setSessionId(a.getSessionId());
        record.setMessageId(a.getMessageId());
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

    /** DO → 领域对象。 */
    private ChatAttachment toDomain(ChatAttachmentDO record) {
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
