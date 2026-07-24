package com.richard.fyoung.customeradmin.workspace.chat.store;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customerwork.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminChatAttachmentStore} 单测：领域对象 ↔ DO 映射、以及 agent_code 经线程上下文补写/清理。
 *
 * <p>用 Mock 的 {@link AiChatAttachmentMapper}（不依赖真库，与仓库现有 Store/Service 单测同款）。
 * 重点校验 agent_code 落库方案：{@code bindAgentCode} 后 save 写入该值，{@code clearAgentCode} 后 save 写空串
 * （防线程复用串号）。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminChatAttachmentStoreTest {

    private AiChatAttachmentMapper mapper;
    private AdminChatAttachmentStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(AiChatAttachmentMapper.class);
        store = new AdminChatAttachmentStore(mapper);
    }

    @Test
    void save_shouldWriteBoundAgentCode() {
        store.bindAgentCode("agent-x");
        try {
            store.save(sample("a1"));
        } finally {
            store.clearAgentCode();
        }

        ArgumentCaptor<AiChatAttachment> captor = ArgumentCaptor.forClass(AiChatAttachment.class);
        verify(mapper).insert(captor.capture());
        AiChatAttachment saved = captor.getValue();
        assertEquals("agent-x", saved.getAgentCode());
        assertEquals("a1", saved.getId());
        assertEquals("SUCCESS", saved.getParseStatus());
    }

    @Test
    void save_shouldWriteEmptyAgentCode_whenNotBound() {
        // 未绑定（如非上传链路直接调 store）时 agent_code 落空串，不写脏值
        store.save(sample("a2"));

        ArgumentCaptor<AiChatAttachment> captor = ArgumentCaptor.forClass(AiChatAttachment.class);
        verify(mapper).insert(captor.capture());
        assertEquals("", captor.getValue().getAgentCode());
    }

    @Test
    void findById_shouldMapEntityToDomain() {
        AiChatAttachment entity = new AiChatAttachment();
        entity.setId("a3");
        entity.setSessionId("s3");
        entity.setMessageId("m3");
        entity.setChannel("admin_chat");
        entity.setFileName("doc.txt");
        entity.setExtension("txt");
        entity.setFileSize(12L);
        entity.setParseStatus("FAILED");
        entity.setErrorMessage("boom");
        entity.setCreatedAt(LocalDateTime.now());
        when(mapper.selectById("a3")).thenReturn(entity);

        Optional<ChatAttachment> found = store.findById("a3");

        assertTrue(found.isPresent());
        ChatAttachment domain = found.get();
        assertEquals("a3", domain.getId());
        assertEquals("m3", domain.getMessageId());
        assertEquals("doc.txt", domain.getFileName());
        assertEquals(AttachmentParseStatus.FAILED, domain.getParseStatus());
        assertEquals("boom", domain.getErrorMessage());
        assertEquals(12L, domain.getFileSize());
    }

    @Test
    void save_shouldPersistMessageId() {
        store.save(sample("a4"));

        ArgumentCaptor<AiChatAttachment> captor = ArgumentCaptor.forClass(AiChatAttachment.class);
        verify(mapper).insert(captor.capture());
        assertEquals("msg-a4", captor.getValue().getMessageId());
    }

    @Test
    void bindToMessage_shouldUpdateSessionAndMessageId_andReturnCount() {
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);

        int updated = store.bindToMessage("agent-x", "sess-1", "msg-1", List.of("a1", "a2"));

        assertEquals(2, updated);
        verify(mapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void findByIdAndAgentCode_shouldReturnDomain_whenMatched() {
        AiChatAttachment entity = new AiChatAttachment();
        entity.setId("a5");
        entity.setAgentCode("agent-x");
        entity.setFileName("f.pdf");
        entity.setMimeType("application/pdf");
        entity.setStoragePath("202607/a5.pdf");
        entity.setFileSize(9L);
        entity.setParseStatus("SUCCESS");
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        Optional<ChatAttachment> found = store.findByIdAndAgentCode("a5", "agent-x");

        assertTrue(found.isPresent());
        assertEquals("202607/a5.pdf", found.get().getStoragePath());
        assertEquals("application/pdf", found.get().getMimeType());
    }

    @Test
    void findByIdAndAgentCode_shouldBeEmpty_whenNoMatch() {
        // agent_code 不匹配 / 附件不存在：SQL 查不到 → 空（Service 层据此 fast-fail 成 NOT_FOUND）
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertFalse(store.findByIdAndAgentCode("a6", "other-agent").isPresent());
    }

    private ChatAttachment sample(String id) {
        return ChatAttachment.builder()
            .id(id)
            .sessionId("s")
            .messageId("msg-" + id)
            .uploader("u")
            .channel("admin_chat")
            .fileName("f.txt")
            .extension("txt")
            .mimeType("text/plain")
            .fileSize(3L)
            .storagePath("202607/" + id + ".txt")
            .parseStatus(AttachmentParseStatus.SUCCESS)
            .parsedText("hello")
            .createdAt(LocalDateTime.now())
            .build();
    }
}
