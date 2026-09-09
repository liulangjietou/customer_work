package com.richard.fyoung.customerwork.data.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档文本提取：只做「字节 → 正文」。
 *
 * @author owlzhangfq@gmail.com
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(
        List.of(new TextAttachmentParser(), new ExcelMarkdownParser(), new TikaDocumentParser()));

    @Test
    @DisplayName("提取纯文本文件的正文")
    void extractsPlainText() {
        String text = extractor.extract("退货政策：七天无理由。".getBytes(StandardCharsets.UTF_8), "policy.txt");

        assertEquals("退货政策：七天无理由。", text);
    }

    /**
     * 图片刻意不在白名单里。
     *
     * <p>图片要走视觉模型 OCR，那是一次有成本、依赖外部服务的调用。知识库入库是批量操作，
     * 隐式把模型调用带进来会让「传一批文件」变成一笔说不清的账单，
     * 入库成功率还会取决于模型可用性。</p>
     */
    @Test
    @DisplayName("图片不在允许类型里，不会隐式触发视觉模型调用")
    void rejectsImages() {
        assertFalse(DocumentTextExtractor.supports("screenshot.png"));
        assertFalse(DocumentTextExtractor.supports("photo.jpg"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> extractor.extract(new byte[]{1, 2, 3}, "screenshot.png"));
        assertTrue(e.getMessage().contains("png"));
    }

    @Test
    @DisplayName("可执行文件等类型一律拒绝")
    void rejectsUnknownTypes() {
        assertFalse(DocumentTextExtractor.supports("payload.exe"));
        assertFalse(DocumentTextExtractor.supports("noextension"));
        assertThrows(IllegalArgumentException.class,
            () -> extractor.extract("x".getBytes(StandardCharsets.UTF_8), "payload.exe"));
    }

    /**
     * 提取不出文本必须失败，不能存成一条空知识。
     *
     * <p>扫描件 PDF 是最常见的一种：有页面、没有文本层。静默存进去会变成一条
     * 永远召不回、也没人知道为什么的记录。</p>
     */
    @Test
    @DisplayName("提取不到正文时报错，而不是存一条空知识")
    void failsOnEmptyText() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> extractor.extract("   \n  ".getBytes(StandardCharsets.UTF_8), "blank.txt"));
        assertTrue(e.getMessage().contains("未能从文件中提取到文本"));
    }

    @Test
    @DisplayName("空文件与缺文件名一律拒绝")
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(new byte[0], "a.txt"));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(null, "a.txt"));
        assertThrows(IllegalArgumentException.class,
            () -> extractor.extract("x".getBytes(StandardCharsets.UTF_8), ""));
    }

    @Test
    @DisplayName("没有任何解析器时构造即失败，不留一个什么都提取不出的实例")
    void requiresAtLeastOneParser() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentTextExtractor(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentTextExtractor(null));
    }

    @Test
    @DisplayName("允许类型清单对外可读，前端与接口文档共用同一份")
    void exposesAllowedExtensions() {
        assertTrue(DocumentTextExtractor.allowedExtensions().contains("pdf"));
        assertTrue(DocumentTextExtractor.allowedExtensions().contains("docx"));
        assertFalse(DocumentTextExtractor.allowedExtensions().contains("png"));
    }
}
