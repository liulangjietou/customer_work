package com.richard.fyoung.customerwork.data.attachment;

import org.apache.tika.Tika;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 文档文本提取：只做「字节 → 正文」这一件事。
 *
 * <h3>它与 {@link AttachmentParseService} 的分工</h3>
 * <p>那个类是<b>对话附件</b>的完整编排——落 MinIO、写 {@code cw_chat_attachment}、
 * 绑定 sessionId 与上传者、解析失败落 {@code FAILED} 状态。知识库入库要的只是正文文本，
 * 用它等于顺带在附件表里造一批与任何会话都无关的记录。因此这里只复用
 * {@link AttachmentParser} SPI 与 Tika 的类型探测，把存储与状态机留在原处。</p>
 *
 * <h3>为什么白名单里没有图片</h3>
 * <p>图片走的是 {@code VisionOcrParser}，那意味着一次真实的视觉模型调用——有成本、
 * 有外部依赖、可能因欠费或限流失败。知识库入库是运营的批量操作，把模型调用隐式带进来，
 * 会让「传一批文件」变成一笔说不清的账单，也让入库成功率取决于模型可用性。
 * 需要图片入库时应当是显式选择，而不是白名单里多一个扩展名。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class DocumentTextExtractor {

    /** 允许入库的文档类型。刻意不含图片，理由见类注释。 */
    private static final Set<String> ALLOWED = Set.of(
        "txt", "md", "markdown", "csv", "json", "xml", "log",
        "pdf", "doc", "docx", "ppt", "pptx", "html", "htm",
        "xls", "xlsx");

    private final List<AttachmentParser> parsers;
    private final Tika tika = new Tika();

    public DocumentTextExtractor(List<AttachmentParser> parsers) {
        if (CollectionUtils.isEmpty(parsers)) {
            throw new IllegalArgumentException("至少要有一个 AttachmentParser，否则任何文件都提取不出文本");
        }
        this.parsers = List.copyOf(parsers);
    }

    /** 这个扩展名是否允许入库（供调用方在读取文件内容之前先挡一道）。 */
    public static boolean supports(String fileName) {
        return ALLOWED.contains(extension(fileName));
    }

    /** 允许的扩展名，供接口文档与前端提示复用，避免两处各写一份清单。 */
    public static Set<String> allowedExtensions() {
        return ALLOWED;
    }

    /**
     * 提取正文。
     *
     * <p>不做兜底：提取不出文本的文件不该被静默存成一条空知识——那会在检索时
     * 变成一条永远召不回、也没人知道为什么的记录。失败一律抛给调用方决定怎么告诉用户。</p>
     *
     * @throws IllegalArgumentException 文件为空、类型不支持，或解析出的正文为空
     * @throws IllegalStateException    解析器执行失败
     */
    public String extract(byte[] data, String fileName) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("缺少文件名，无法判断文件类型");
        }
        String ext = extension(fileName);
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：" + (ext.isEmpty() ? fileName : ext));
        }
        String mime = detectMime(data, fileName);
        AttachmentParser parser = parsers.stream()
            .filter(p -> p.supports(ext, mime))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("没有解析器能处理该类型：" + ext));

        String text;
        try {
            text = parser.parse(data, fileName, ext, mime).text();
        } catch (Exception e) {
            throw new IllegalStateException("文件解析失败：" + fileName, e);
        }
        if (!StringUtils.hasText(text)) {
            // 扫描件 PDF 是最常见的一种：有页面、没有文本层
            throw new IllegalArgumentException(
                "未能从文件中提取到文本，若为扫描件请先做 OCR 或改用文本格式：" + fileName);
        }
        return text.strip();
    }

    private String detectMime(byte[] data, String fileName) {
        try {
            return tika.detect(data, fileName);
        } catch (Exception e) {
            // 探测失败不致命：parser 主要按扩展名判定，mime 只是辅助
            return "application/octet-stream";
        }
    }

    private static String extension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
