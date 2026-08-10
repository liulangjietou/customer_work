package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.data.attachment.mapper.ChatAttachmentMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 附件存储测试（对接本机 MySQL：localhost:3306，root/root，库 agent_scope_customer_work）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；在线时真实执行存-取-按会话列表往返。各用例用 UUID 生成唯一
 * id/session，避免与既有数据或并行用例互相污染。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisAttachmentStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private MybatisAttachmentStore store;
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-attachment-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        store = new MybatisAttachmentStore(MybatisTestSupport.mapper(dataSource, ChatAttachmentMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ChatAttachment sample(String id, String session, AttachmentParseStatus status) {
        return ChatAttachment.builder()
            .id(id).sessionId(session).messageId("msg-" + id).uploader("u-" + id).channel("user_chat")
            .fileName("f-" + id + ".pdf").extension("pdf").mimeType("application/pdf")
            .fileSize(1024).storagePath("202607/" + id + ".pdf")
            .parseStatus(status).parsedText(status == AttachmentParseStatus.SUCCESS ? "解析文本" : null)
            .errorMessage(status == AttachmentParseStatus.FAILED ? "parse error" : null)
            .createdAt(LocalDateTime.now()).build();
    }

    @Test
    void saveAndFindById_shouldRoundTrip() {
        String id = UUID.randomUUID().toString().replace("-", "");
        store.save(sample(id, "s-" + id, AttachmentParseStatus.SUCCESS));

        ChatAttachment found = store.findById(id).orElseThrow();
        assertEquals("application/pdf", found.getMimeType());
        assertEquals("msg-" + id, found.getMessageId());
        assertEquals(AttachmentParseStatus.SUCCESS, found.getParseStatus());
        assertEquals("解析文本", found.getParsedText());
        assertEquals(1024, found.getFileSize());
    }

    @Test
    void save_shouldPersistFailedRecord() {
        String id = UUID.randomUUID().toString().replace("-", "");
        store.save(sample(id, "s-" + id, AttachmentParseStatus.FAILED));

        ChatAttachment found = store.findById(id).orElseThrow();
        assertEquals(AttachmentParseStatus.FAILED, found.getParseStatus());
        assertEquals("parse error", found.getErrorMessage());
    }

    @Test
    void listBySession_shouldReturnSessionAttachments() {
        String session = "s-list-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            String id = UUID.randomUUID().toString().replace("-", "");
            store.save(sample(id, session, AttachmentParseStatus.SUCCESS));
        }
        List<ChatAttachment> list = store.listBySession(session);
        assertEquals(3, list.size());
        assertTrue(list.stream().allMatch(a -> session.equals(a.getSessionId())));
    }
}
