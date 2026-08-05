package com.richard.fyoung.customerwork.sqlkit;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * xlsx 导出回归测试：必须真实创建 POI 工作簿并回读校验。
 *
 * <p>EasyExcel 与 agentscope-extensions-rag-simple 传递的 poi-ooxml 存在版本对齐要求
 * （easyexcel 3.x 的 poi 4.1.2 与 rag-simple 的 poi-ooxml 5.5.1 混搭时 SXSSFWorkbook 静态初始化
 * NoClassDefFoundError，实测踩过、且纯 CRUD 测试拦不住）——本用例的核心价值就是让构建期真正走一次
 * 该类加载路径。下游模块若自己钉了 POI 版本，需在自己模块内保留同款回归用例。</p>
 * @author owlzhangfq@gmail.com
 */
class XlsxExporterTest {

    @Test
    void writeAndReadBack() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("会话ID", "s-001");
        row.put("创建时间", "2026-07-14 16:00:00");

        byte[] bytes = XlsxExporter.write(List.of("id", "会话ID", "创建时间"), List.of(row));
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("会话ID");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("s-001");
        }
    }

    @Test
    void writeShouldLeaveCellEmpty_whenRowMissesColumn() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);

        byte[] bytes = XlsxExporter.write(List.of("id", "missing"), List.of(row));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("missing");
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1d);
            // 缺列的单元格不写值：POI 回读可能是 null / 空白单元格 / 空串，都视为"未写值"
            Cell missing = sheet.getRow(1).getCell(1);
            boolean empty = missing == null
                || missing.getCellType() == CellType.BLANK
                || (missing.getCellType() == CellType.STRING && missing.getStringCellValue().isEmpty());
            assertThat(empty).as("缺列单元格应为空，实际: %s", missing).isTrue();
        }
    }

    @Test
    void writeEmptyResult() {
        assertThat(XlsxExporter.write(null, null)).isNotEmpty();
    }
}
