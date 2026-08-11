package com.richard.fyoung.customerwork.data.attachment;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.ByteArrayInputStream;
import java.util.Set;

/**
 * 富文档文本解析器：pdf / doc / docx / ppt / pptx / html 交给 Tika 自动识别并抽正文。
 *
 * <p>用 {@link AutoDetectParser} + {@link BodyContentHandler}（写入上限 {@code -1} 关闭默认 10 万字符限制，
 * 长文档不被截断——真正的长度截断由编排层按 max-parsed-chars 统一控制）。Excel 不走这里（另有
 * {@link ExcelMarkdownParser} 转 Markdown 表格，对 LLM 更友好）。</p>
 * @author owlzhangfq@gmail.com
 */
public class TikaDocumentParser implements AttachmentParser {

    /** 交给 Tika 的富文档扩展名（不含 Excel）。 */
    private static final Set<String> SUPPORTED = Set.of("pdf", "doc", "docx", "ppt", "pptx", "html");

    @Override
    public boolean supports(String ext, String mime) {
        return SUPPORTED.contains(ext);
    }

    @Override
    public ParsedContent parse(byte[] data, String fileName, String ext, String mime) throws Exception {
        // -1 关闭 BodyContentHandler 默认写入上限，避免长文档被 Tika 提前截断
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        }
        return ParsedContent.of(handler.toString().trim());
    }
}
