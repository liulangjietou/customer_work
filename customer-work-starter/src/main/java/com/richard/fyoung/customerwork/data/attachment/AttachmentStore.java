package com.richard.fyoung.customerwork.data.attachment;

import java.util.List;
import java.util.Optional;

/**
 * 附件存储 SPI（持久化扩展点）。
 *
 * <p>默认 {@link InMemoryAttachmentStore}（进程内，离线可测）；生产按 {@code customer-work.attachment.store-mode=jdbc}
 * 切换为 {@link MybatisAttachmentStore}（落 {@code cw_chat_attachment}）。下游（如 admin，用自有 {@code ai_chat_attachment}
 * 表）声明同类型 Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AttachmentStore {

    /** 保存一条附件记录（新建）。 */
    void save(ChatAttachment attachment);

    /** 按附件 ID 查找。 */
    Optional<ChatAttachment> findById(String id);

    /** 查该会话的附件列表（按创建时间倒序）。 */
    List<ChatAttachment> listBySession(String sessionId);
}
