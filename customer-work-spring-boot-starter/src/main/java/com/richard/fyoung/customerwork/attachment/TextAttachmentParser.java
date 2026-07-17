package com.richard.fyoung.customerwork.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 纯文本解析器：md / txt / csv / json 直接按 UTF-8 读取。
 *
 * <p>这些格式本身即文本，无需任何富文档解析，直读最快最稳。</p>
 * @author owlzhangfq@gmail.com
 */
public class TextAttachmentParser implements AttachmentParser {

    /** 支持的纯文本扩展名。 */
    private static final Set<String> SUPPORTED = Set.of("md", "txt", "csv", "json");

    @Override
    public boolean supports(String ext, String mime) {
        return SUPPORTED.contains(ext);
    }

    @Override
    public ParsedContent parse(byte[] data, String fileName, String ext, String mime) {
        return ParsedContent.of(new String(data, StandardCharsets.UTF_8));
    }
}
