package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.safety.security.UserPrincipals;
import com.richard.fyoung.customerwork.data.attachment.AttachmentParseService;
import com.richard.fyoung.customerwork.data.attachment.ChatAttachment;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerworkapp.service.UserSessionGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 用户端聊天附件上传解析端点（WebFlux）。
 *
 * <p>接收 multipart 文件（字段名 {@code file}，可选 {@code sessionId}），委托 starter 的
 * {@link AttachmentParseService} 落盘 + 解析（图片走视觉 OCR、pdf/office/html 走 Tika/POI、md/txt/csv/json 直读）+ 落库，
 * 返回裸 JSON {@code {id, fileName, content, parseStatus, errorMessage}}（app 无统一 Result 包装，与前端契约一致）。
 * 上传者取 {@link UserAuthWebFilter} 已验证的当前登录用户，
 * 未登录 401；{@code channel} 固定 {@code user_chat}。文件类型 / 大小的唯一防御在 AttachmentParseService，
 * 非法即抛 {@link IllegalArgumentException}，此处翻译为 400。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer")
@Tag(name = "聊天附件", description = "上传并解析聊天附件（多格式）")
public class AttachmentController {

    /** 用户端渠道标识（固定）。 */
    private static final String CHANNEL_USER_CHAT = "user_chat";

    /** join 空流时的兜底空字节数组（空文件统一由 AttachmentParseService 拒绝）。 */
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final AttachmentParseService attachmentParseService;
    private final UserSessionGuard sessionGuard;

    public AttachmentController(AttachmentParseService attachmentParseService,
                                UserSessionGuard sessionGuard) {
        this.attachmentParseService = attachmentParseService;
        this.sessionGuard = sessionGuard;
    }

    /**
     * 附件上传解析响应（裸 JSON，与前端 {@code ChatAttachmentResult} 对齐）。
     *
     * @param id           附件 ID
     * @param fileName     原始文件名
     * @param content      解析出的文本（FAILED 时为空串）
     * @param parseStatus  解析状态 SUCCESS / FAILED
     * @param errorMessage 解析失败原因（SUCCESS 时为 null）
     */
    public record AttachmentResponse(String id, String fileName, String content,
                                     String parseStatus, String errorMessage) {
    }

    @Operation(summary = "上传聊天附件", description = "需 Bearer 令牌；multipart 字段名 file，可选 sessionId；"
        + "支持 md/txt/csv/json/html/pdf/doc(x)/xls(x)/ppt(x)/图片；返回解析文本，解析失败返回 parseStatus=FAILED")
    @PostMapping(value = "/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<AttachmentResponse> upload(
        @RequestPart("file") FilePart file,
        @RequestPart(value = "sessionId", required = false) String sessionId,
        ServerWebExchange exchange) {
        UserPrincipal principal = UserPrincipals.require(exchange);
        if (sessionId != null && !sessionId.isBlank()) {
            sessionGuard.requireOwned(sessionId, principal.userId());
        }
        // 先把响应式分片汇聚为完整字节，再切到 boundedElastic 执行阻塞式落盘 / 解析 / 落库
        return DataBufferUtils.join(file.content())
            .map(AttachmentController::toBytes)
            // 0 字节分片可能让 join 直接完成为空流，兜底空字节数组交由唯一防御点拒绝（400），避免 200 空响应
            .defaultIfEmpty(EMPTY_BYTES)
            .flatMap(bytes -> Mono.fromCallable(() -> attachmentParseService.parseAndStore(
                    bytes, file.filename(), sessionId, principal.userId(), CHANNEL_USER_CHAT))
                .subscribeOn(Schedulers.boundedElastic()))
            .map(AttachmentController::toResponse)
            // 白名单外 / 空文件 / 超大小上限：唯一防御点抛 IllegalArgumentException，翻译为 400
            .onErrorMap(IllegalArgumentException.class,
                e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    /** 汇聚后的 DataBuffer 转字节并释放，避免堆外内存泄漏。 */
    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /** 领域对象映射为前端契约；FAILED 时解析文本为 null，统一收敛为空串。 */
    private static AttachmentResponse toResponse(ChatAttachment attachment) {
        String content = attachment.getParsedText() == null ? "" : attachment.getParsedText();
        return new AttachmentResponse(
            attachment.getId(),
            attachment.getFileName(),
            content,
            attachment.getParseStatus().name(),
            attachment.getErrorMessage());
    }

}
