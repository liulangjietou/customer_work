package com.richard.fyoung.customerwork.attachment;

import java.util.Set;

/**
 * 图片解析器：png / jpg / jpeg / bmp / webp 委托 {@link VisionOcrService} 走视觉大模型 OCR。
 *
 * <p>不用 tesseract，改用视觉大模型直接识别（对复杂排版 / 手写 / 表格截图更鲁棒）。本类只负责分派，
 * 识别逻辑与容错在 {@link VisionOcrService} 实现里。</p>
 * @author owlzhangfq@gmail.com
 */
public class VisionOcrParser implements AttachmentParser {

    /** 支持的图片扩展名。 */
    private static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "bmp", "webp");

    private final VisionOcrService visionOcrService;

    public VisionOcrParser(VisionOcrService visionOcrService) {
        this.visionOcrService = visionOcrService;
    }

    @Override
    public boolean supports(String ext, String mime) {
        return SUPPORTED.contains(ext);
    }

    @Override
    public ParsedContent parse(byte[] data, String fileName, String ext, String mime) {
        return ParsedContent.of(visionOcrService.recognize(data, mime));
    }
}
