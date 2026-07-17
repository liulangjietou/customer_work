package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.UserJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件上传端点切片测试：正常解析、白名单外扩展名 400、解析失败响应、未登录 401。
 *
 * <p>不依赖外部网络 / 真实模型：{@link AttachmentParseService} 全程 mock，只验证 Controller 的鉴权、
 * 字节汇聚、契约映射与异常翻译。JWT 用真实 {@link UserJwtService}（对称密钥来自 {@link CustomerWorkProperties}）。</p>
 * @author owlzhangfq@gmail.com
 */
@WebFluxTest(AttachmentController.class)
@Import({CustomerWorkProperties.class, UserJwtService.class})
class AttachmentControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserJwtService jwtService;

    @MockBean
    private AttachmentParseService attachmentParseService;

    /** 构造一条解析成功的附件记录。 */
    private ChatAttachment success(String fileName, String text) {
        return ChatAttachment.builder()
            .id("A-1")
            .sessionId("S-1")
            .uploader("U-1")
            .channel("user_chat")
            .fileName(fileName)
            .extension("txt")
            .mimeType("text/plain")
            .fileSize(text.length())
            .storagePath("202607/A-1.txt")
            .parseStatus(AttachmentParseStatus.SUCCESS)
            .parsedText(text)
            .createdAt(LocalDateTime.now())
            .build();
    }

    /** 构造一条解析失败的附件记录（文件仍落盘落库，parsedText 为空）。 */
    private ChatAttachment failed(String fileName, String errorMessage) {
        return ChatAttachment.builder()
            .id("A-2")
            .fileName(fileName)
            .extension("pdf")
            .parseStatus(AttachmentParseStatus.FAILED)
            .errorMessage(errorMessage)
            .createdAt(LocalDateTime.now())
            .build();
    }

    private MultipartBodyBuilder filePart(String fileName, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes())).filename(fileName);
        return builder;
    }

    @Test
    void upload_txt_withValidToken_shouldReturnParsedContent() {
        when(attachmentParseService.parseAndStore(any(), eq("note.txt"), any(), eq("U-1"), eq("user_chat")))
            .thenReturn(success("note.txt", "hello attachment"));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.post().uri("/api/customer/attachment")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(filePart("note.txt", "hello attachment").build())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.id").isEqualTo("A-1")
            .jsonPath("$.fileName").isEqualTo("note.txt")
            .jsonPath("$.content").isEqualTo("hello attachment")
            .jsonPath("$.parseStatus").isEqualTo("SUCCESS")
            .jsonPath("$.errorMessage").doesNotExist();

        // uploader 必须取当前登录用户、channel 固定 user_chat
        verify(attachmentParseService).parseAndStore(any(), eq("note.txt"), any(), eq("U-1"), eq("user_chat"));
    }

    @Test
    void upload_parseFailed_shouldReturn200WithFailedStatus() {
        when(attachmentParseService.parseAndStore(any(), eq("broken.pdf"), any(), any(), any()))
            .thenReturn(failed("broken.pdf", "corrupt pdf"));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.post().uri("/api/customer/attachment")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(filePart("broken.pdf", "%PDF-broken").build())
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.parseStatus").isEqualTo("FAILED")
            .jsonPath("$.content").isEqualTo("")
            .jsonPath("$.errorMessage").isEqualTo("corrupt pdf");
    }

    @Test
    void upload_unsupportedExtension_shouldReturn400() {
        // 白名单外扩展名：唯一防御点抛 IllegalArgumentException，Controller 翻译为 400
        when(attachmentParseService.parseAndStore(any(), eq("virus.exe"), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("unsupported file type: exe"));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.post().uri("/api/customer/attachment")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(filePart("virus.exe", "MZ").build())
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void upload_emptyFile_shouldReturn400() {
        // 0 字节文件：join 可能完成为空流，Controller 需兜底空字节数组送入唯一防御点被拒，绝不能 200 空响应
        when(attachmentParseService.parseAndStore(any(), eq("empty.txt"), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("empty file"));
        String token = jwtService.issue("U-1", "alice", "Alice");

        webTestClient.post().uri("/api/customer/attachment")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(filePart("empty.txt", "").build())
            .exchange()
            .expectStatus().isBadRequest();

        verify(attachmentParseService).parseAndStore(any(), eq("empty.txt"), any(), any(), any());
    }

    @Test
    void upload_withoutToken_shouldReturn401() {
        webTestClient.post().uri("/api/customer/attachment")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(filePart("note.txt", "hello").build())
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
