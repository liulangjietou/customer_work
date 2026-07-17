package com.richard.fyoung.customerwork.attachment;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 解析器单测：现场用 POI 生成 xlsx 字节，验证逐 sheet 转 Markdown 表格。
 * @author owlzhangfq@gmail.com
 */
class ExcelMarkdownParserTest {

    private final ExcelMarkdownParser parser = new ExcelMarkdownParser();

    private byte[] sampleXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("订单");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("年龄");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("张三");
            data.createCell(1).setCellValue(28);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void parse_shouldRenderMarkdownTable() throws Exception {
        ParsedContent content = parser.parse(sampleXlsx(), "data.xlsx", "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String md = content.text();
        assertTrue(md.contains("## 订单"), "应含 sheet 名标题");
        assertTrue(md.contains("| 姓名 |"), "应含表头单元格");
        assertTrue(md.contains("| --- |"), "应含 Markdown 表头分隔行");
        assertTrue(md.contains("张三"), "应含数据行");
        assertTrue(md.contains("28"), "数字应格式化输出");
    }

    @Test
    void supports_shouldMatchExcelExtensions() {
        assertTrue(parser.supports("xls", ""));
        assertTrue(parser.supports("xlsx", ""));
    }
}
