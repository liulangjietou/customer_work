package com.richard.fyoung.customeradmin.workspace.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallMetaFactory;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatAttachmentDTO;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatAttachmentService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatHistoryService;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.session.service.WorkspaceSessionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import org.mockito.MockedStatic;

/**
 * {@link ChatController} 附件详情/下载端点单测（纯单元，构造器注入 mock 服务）：
 * 重点验证下载端点的响应头契约——Content-Type 取库中 mime、Content-Disposition 用 RFC 5987
 * {@code filename*=UTF-8''<url编码>}（中文名可解）、附带 {@code X-Content-Type-Options: nosniff}。
 * @author owlzhangfq@gmail.com
 */
class ChatControllerAttachmentTest {

    private static final String AGENT_CODE = "coder";
    private static final Long CURRENT_USER = 7L;

    private final ChatAttachmentService chatAttachmentService = mock(ChatAttachmentService.class);
    private final ChatController controller = new ChatController(
        mock(ChatService.class), mock(ChatHistoryService.class),
        chatAttachmentService, mock(AgentCallMetaFactory.class), mock(WorkspaceSessionGuard.class));

    @Test
    void attachmentDetail_shouldDelegateToService() {
        ChatAttachmentDTO dto = new ChatAttachmentDTO("att-1", "spec.pdf", "文本", "SUCCESS", null,
            "application/pdf", 2048L);
        when(chatAttachmentService.getDetail(AGENT_CODE, "att-1", CURRENT_USER)).thenReturn(dto);

        Result<ChatAttachmentDTO> result;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            result = controller.attachmentDetail(AGENT_CODE, "att-1");
        }

        assertEquals(dto, result.getData());
        verify(chatAttachmentService).getDetail(eq(AGENT_CODE), eq("att-1"), eq(CURRENT_USER));
    }

    @Test
    void attachmentFile_shouldSetDownloadHeaders_withEncodedChineseName() {
        byte[] bytes = "原始字节".getBytes(StandardCharsets.UTF_8);
        when(chatAttachmentService.loadFile(AGENT_CODE, "att-2", CURRENT_USER))
            .thenReturn(new ChatAttachmentService.LoadedFile(bytes, "text/plain", "原始 文件.txt"));

        ResponseEntity<byte[]> response;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            response = controller.attachmentFile(AGENT_CODE, "att-2");
        }

        assertArrayEquals(bytes, response.getBody());
        HttpHeaders headers = response.getHeaders();
        assertEquals("text/plain", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        // RFC 5987：filename* 前缀 + UTF-8 url 编码（空格编成 %20 而非 +）
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertEquals("attachment; filename*=UTF-8''%E5%8E%9F%E5%A7%8B%20%E6%96%87%E4%BB%B6.txt", disposition);
        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
    }

    @Test
    void attachmentFile_shouldFallBackToOctetStream_whenMimeIsOctet() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(chatAttachmentService.loadFile(AGENT_CODE, "att-3", CURRENT_USER))
            .thenReturn(new ChatAttachmentService.LoadedFile(bytes, "application/octet-stream", "blob.bin"));

        ResponseEntity<byte[]> response;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(CURRENT_USER);
            response = controller.attachmentFile(AGENT_CODE, "att-3");
        }

        assertEquals("application/octet-stream", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    }
}
