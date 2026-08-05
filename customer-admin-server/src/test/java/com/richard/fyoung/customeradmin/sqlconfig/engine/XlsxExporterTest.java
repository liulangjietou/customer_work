package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
import com.richard.fyoung.customerwork.sqlkit.XlsxExporter;
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
 * <p>导出实现已下沉 starter（{@link XlsxExporter}），本用例<b>刻意保留在 admin 侧</b>：真正加载
 * EasyExcel/POI 的是本模块的运行期 classpath（{@code SqlQueryController#export} 调 starter 导出），
 * 而 EasyExcel 与 rag-simple 传递的 poi-ooxml 存在版本对齐要求（easyexcel 3.x 的 poi 4.1.2 与
 * poi-ooxml 5.5.1 混搭时 SXSSFWorkbook 静态初始化 NoClassDefFoundError，实测踩过、纯 CRUD 测试拦不住）。
 * 只在 starter 侧测无法覆盖本模块解析出的 classpath，故两边各留一份。</p>
 *
 * <p>调用形态与 Controller 保持一致（{@code write(result.getColumns(), result.getRows())}），
 * 避免测试与生产走不同入参路径。</p>
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

        byte[] bytes = XlsxExporter.write(result.getColumns(), result.getRows());
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
        assertThat(XlsxExporter.write(result.getColumns(), result.getRows())).isNotEmpty();
    }
}
