package com.richard.fyoung.customerwork.attachment;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 附件解析编排入口（<b>全链路唯一防御点</b>）。
 *
 * <p>流程：入参校验（白名单 + 大小）→ 落盘 → 选解析器解析（超长截断）→ 落库 → 返回 {@link ChatAttachment}。
 * 校验不通过（不在白名单 / 超大小上限）直接抛 {@link IllegalArgumentException}，由上层接口转 4xx；解析异常
 * 统一 {@code catch(Exception)} 落 FAILED 记录并正常返回（文件已落盘、记录已落库，不把 500 抛给上传接口）。
 * fast fail 原则：白名单 / 大小校验只在本类做一次，各解析器内部不再重复校验。</p>
 * @author owlzhangfq@gmail.com
 */
public class AttachmentParseService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentParseService.class);

    /** 富文档 / 图片扩展名（文本类见 {@link TextAttachmentParser#TEXT_EXTENSIONS}，二者并集构成内置白名单）。 */
    private static final Set<String> BINARY_WHITELIST = Set.of(
        "html",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "png", "jpg", "jpeg", "bmp", "webp");

    /** 扩展名 → MIME 主映射（推断以扩展名为主，Tika detect 做兜底）。 */
    private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
        Map.entry("md", "text/markdown"),
        Map.entry("txt", "text/plain"),
        Map.entry("csv", "text/csv"),
        Map.entry("json", "application/json"),
        Map.entry("html", "text/html"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("doc", "application/msword"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("ppt", "application/vnd.ms-powerpoint"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("bmp", "image/bmp"),
        Map.entry("webp", "image/webp"));

    /** 超长截断后追加的提示。 */
    private static final String TRUNCATED_SUFFIX = "\n\n[内容超长已截断]";

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final List<AttachmentParser> parsers;
    private final AttachmentStore store;
    private final AttachmentFileStorage fileStorage;
    private final AttachmentProperties properties;
    private final Tika tika = new Tika();

    public AttachmentParseService(List<AttachmentParser> parsers,
                                  AttachmentStore store,
                                  AttachmentFileStorage fileStorage,
                                  AttachmentProperties properties) {
        this.parsers = parsers;
        this.store = store;
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    /**
     * 解析并存储一个附件。
     *
     * @param data      文件字节
     * @param fileName  原始文件名
     * @param sessionId 会话 ID（可空）
     * @param uploader  上传者标识（可空）
     * @param channel   来源渠道：user_chat / admin_chat / vibecoding
     * @return 附件记录（含解析文本或失败原因）
     * @throws IllegalArgumentException 文件为空 / 扩展名不在白名单 / 超过大小上限
     */
    public ChatAttachment parseAndStore(byte[] data, String fileName, String sessionId,
                                        String uploader, String channel) {
        // —— 唯一防御点：入参校验 ——
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("empty file");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("missing file name");
        }
        String ext = extractExtension(fileName);
        if (!isAllowed(ext)) {
            throw new IllegalArgumentException("unsupported file type: " + (ext.isEmpty() ? fileName : ext));
        }
        long maxBytes = (long) properties.getMaxFileSizeMb() * BYTES_PER_MB;
        if (data.length > maxBytes) {
            throw new IllegalArgumentException(
                "file too large: " + data.length + " bytes, limit " + properties.getMaxFileSizeMb() + "MB");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String mime = resolveMime(ext, fileName, data);

        // 落盘（失败即抛，视为整体失败——落盘都不成功谈不上可追溯）
        String storagePath;
        try {
            storagePath = fileStorage.store(data, id, ext);
        } catch (Exception e) {
            log.error("attachment store to disk failed, code={}, id={}, file={}",
                "ATTACHMENT-DISK-FAIL", id, fileName, e);
            throw new IllegalStateException("failed to store attachment file: " + fileName, e);
        }

        // 解析（异常兜底：落 FAILED，不抛给上传接口）
        AttachmentParseStatus status;
        String parsedText = null;
        String errorMessage = null;
        try {
            ParsedContent content = selectParser(ext, mime).parse(data, fileName, ext, mime);
            parsedText = truncate(content.text());
            status = AttachmentParseStatus.SUCCESS;
        } catch (Exception e) {
            status = AttachmentParseStatus.FAILED;
            errorMessage = safeMessage(e);
            log.error("attachment parse failed, code={}, id={}, file={}",
                "ATTACHMENT-PARSE-FAIL", id, fileName, e);
        }

        ChatAttachment attachment = ChatAttachment.builder()
            .id(id)
            .sessionId(sessionId == null ? "" : sessionId)
            .uploader(uploader == null ? "" : uploader)
            .channel(channel == null ? "" : channel)
            .fileName(fileName)
            .extension(ext)
            .mimeType(mime)
            .fileSize(data.length)
            .storagePath(storagePath)
            .parseStatus(status)
            .parsedText(parsedText)
            .errorMessage(errorMessage)
            .createdAt(java.time.LocalDateTime.now())
            .build();

        // 落库（best-effort：已落盘 + 已解析，落库失败不该反噬上传响应，仅记录错误）
        try {
            store.save(attachment);
        } catch (Exception e) {
            log.error("attachment persist failed, code={}, id={}", "ATTACHMENT-PERSIST-FAIL", id, e);
        }
        return attachment;
    }

    /** 白名单判定：内置（文本 + 富文档/图片）之外，还接受配置追加的文本扩展名（大小写不敏感）。 */
    private boolean isAllowed(String ext) {
        if (TextAttachmentParser.TEXT_EXTENSIONS.contains(ext) || BINARY_WHITELIST.contains(ext)) {
            return true;
        }
        return properties.getExtraTextExtensions().stream()
            .anyMatch(e -> e.trim().equalsIgnoreCase(ext));
    }

    /** 选取首个命中的解析器；理论上白名单内必有命中，未命中视为解析失败。 */
    private AttachmentParser selectParser(String ext, String mime) {
        return parsers.stream()
            .filter(p -> p.supports(ext, mime))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no parser for extension: " + ext));
    }

    /** 提取小写扩展名（不含点）；无扩展名返回空串。 */
    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    /** MIME 推断：扩展名映射为主，缺失则 Tika detect 兜底。 */
    private String resolveMime(String ext, String fileName, byte[] data) {
        String byExt = MIME_BY_EXT.get(ext);
        if (byExt != null) {
            return byExt;
        }
        try {
            return tika.detect(data, fileName);
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    /** 超长截断：超过 max-parsed-chars 时截断并追加提示。 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int max = properties.getMaxParsedChars();
        if (max > 0 && text.length() > max) {
            return text.substring(0, max) + TRUNCATED_SUFFIX;
        }
        return text;
    }

    /** 失败原因兜底：取异常消息，为空则用异常类名，避免 error_message 落空。 */
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return StringUtils.hasText(msg) ? msg : e.getClass().getSimpleName();
    }
}
