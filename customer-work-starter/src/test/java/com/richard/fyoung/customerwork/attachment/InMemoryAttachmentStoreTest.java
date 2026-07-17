package com.richard.fyoung.customerwork.attachment;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存附件存储单测：保存 / 按 ID 查 / 按会话查（创建时间倒序）。
 * @author owlzhangfq@gmail.com
 */
class InMemoryAttachmentStoreTest {

    private final InMemoryAttachmentStore store = new InMemoryAttachmentStore();

    private ChatAttachment attachment(String id, String session, LocalDateTime createdAt) {
        return ChatAttachment.builder()
            .id(id).sessionId(session).uploader("u1").channel("user_chat")
            .fileName(id + ".txt").extension("txt").mimeType("text/plain")
            .fileSize(3).storagePath("202607/" + id + ".txt")
            .parseStatus(AttachmentParseStatus.SUCCESS).parsedText("x")
            .createdAt(createdAt).build();
    }

    @Test
    void saveAndFindById_shouldRoundTrip() {
        store.save(attachment("a1", "s1", LocalDateTime.now()));
        ChatAttachment found = store.findById("a1").orElseThrow();
        assertEquals("s1", found.getSessionId());
        assertEquals(AttachmentParseStatus.SUCCESS, found.getParseStatus());
    }

    @Test
    void listBySession_shouldReturnDescendingByCreatedAt() {
        LocalDateTime base = LocalDateTime.now();
        store.save(attachment("a1", "s1", base.minusMinutes(2)));
        store.save(attachment("a2", "s1", base.minusMinutes(1)));
        store.save(attachment("a3", "s1", base));
        store.save(attachment("b1", "s2", base));

        List<ChatAttachment> list = store.listBySession("s1");
        assertEquals(3, list.size());
        assertEquals("a3", list.get(0).getId(), "最新的在前");
        assertEquals("a1", list.get(2).getId(), "最旧的在后");
        assertTrue(store.listBySession("s2").size() == 1);
    }
}
