package com.richard.fyoung.customeradmin.workspace.chat.store;

import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;
import com.richard.fyoung.customeradmin.workspace.chat.mapper.AiChatAttachmentMapper;
import com.richard.fyoung.customerwork.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        assertEquals("doc.txt", domain.getFileName());
        assertEquals(AttachmentParseStatus.FAILED, domain.getParseStatus());
        assertEquals("boom", domain.getErrorMessage());
        assertEquals(12L, domain.getFileSize());
    }

    private ChatAttachment sample(String id) {
        return ChatAttachment.builder()
            .id(id)
            .sessionId("s")
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
