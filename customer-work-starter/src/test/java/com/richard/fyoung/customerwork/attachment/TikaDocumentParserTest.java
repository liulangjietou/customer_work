package com.richard.fyoung.customerwork.attachment;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tika 文档解析器单测：现场用 PDFBox 生成 pdf、POI 生成 docx 字节，验证正文抽取。
 * @author owlzhangfq@gmail.com
 */
class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    private byte[] samplePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private byte[] sampleDocx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun run = p.createRun();
            run.setText(text);
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void parse_shouldExtractPdfText() throws Exception {
        ParsedContent content = parser.parse(samplePdf("Hello PDF Attachment"),
            "doc.pdf", "pdf", "application/pdf");
        assertTrue(content.text().contains("Hello PDF Attachment"), "应抽出 PDF 正文");
    }

    @Test
    void parse_shouldExtractDocxText() throws Exception {
        ParsedContent content = parser.parse(sampleDocx("Hello Docx Attachment"),
            "doc.docx", "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertTrue(content.text().contains("Hello Docx Attachment"), "应抽出 docx 正文");
    }

    @Test
    void supports_shouldMatchDocumentExtensionsButNotExcel() {
        assertTrue(parser.supports("pdf", ""));
        assertTrue(parser.supports("docx", ""));
        assertTrue(parser.supports("html", ""));
        org.junit.jupiter.api.Assertions.assertFalse(parser.supports("xlsx", ""), "Excel 走专用解析器");
    }
}
