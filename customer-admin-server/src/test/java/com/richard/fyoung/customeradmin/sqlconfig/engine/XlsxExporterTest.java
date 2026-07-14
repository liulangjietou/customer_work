package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
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
 * <p>EasyExcel 与 starter 传递的 poi-ooxml 存在版本对齐要求（easyexcel 3.x 的 poi 4.1.2 与
 * rag-simple 的 poi-ooxml 5.5.1 混搭时 SXSSFWorkbook 静态初始化 NoClassDefFoundError，实测踩过、
 * 且纯 CRUD 测试拦不住）——本用例的核心价值就是让构建期真正走一次该类加载路径。</p>
 */
class XlsxExporterTest {

    @Test
    void writeAndReadBack() throws Exception {
        SqlQueryResultVO result = new SqlQueryResultVO();
        result.setColumns(List.of("id", "会话ID", "创建时间"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("会话ID", "s-001");
        row.put("创建时间", "2026-07-14 16:00:00");
        result.setRows(List.of(row));

        byte[] bytes = XlsxExporter.write(result);
        assertThat(bytes).isNotEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("会话ID");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("s-001");
        }
    }

    @Test
    void writeEmptyResult() {
        SqlQueryResultVO result = new SqlQueryResultVO();
        result.setColumns(null);
        result.setRows(null);
        assertThat(XlsxExporter.write(result)).isNotEmpty();
    }
}
