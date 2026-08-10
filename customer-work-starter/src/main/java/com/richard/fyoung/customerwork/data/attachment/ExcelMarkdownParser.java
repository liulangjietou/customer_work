package com.richard.fyoung.customerwork.data.attachment;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.util.Set;

/**
 * Excel 解析器：xls / xlsx 用 POI 逐 sheet 转 Markdown 表格。
 *
 * <p>相较 Tika 输出的 tab 分隔纯文本，Markdown 表格（含表头分隔行）对 LLM 更友好、结构更清晰。
 * 用 {@link DataFormatter} 取单元格显示值（数字 / 日期按格式化文本），空表格跳过。</p>
 * @author owlzhangfq@gmail.com
 */
public class ExcelMarkdownParser implements AttachmentParser {

    /** 支持的 Excel 扩展名。 */
    private static final Set<String> SUPPORTED = Set.of("xls", "xlsx");

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String ext, String mime) {
        return SUPPORTED.contains(ext);
    }

    @Override
    public ParsedContent parse(byte[] data, String fileName, String ext, String mime) throws Exception {
        StringBuilder sb = new StringBuilder();
        int sheetCount;
        try (ByteArrayInputStream in = new ByteArrayInputStream(data);
             Workbook workbook = WorkbookFactory.create(in)) {
            sheetCount = workbook.getNumberOfSheets();
            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }
                sb.append("## ").append(sheet.getSheetName()).append("\n\n");
                appendSheetAsMarkdown(sheet, sb);
                sb.append("\n");
            }
        }
        return ParsedContent.of(sb.toString().trim(), "sheets=" + sheetCount);
    }

    /** 把单个 sheet 渲染成 Markdown 表格（首行作表头，其后补分隔行）。 */
    private void appendSheetAsMarkdown(Sheet sheet, StringBuilder sb) {
        int lastRow = sheet.getLastRowNum();
        int maxCols = 0;
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                maxCols = Math.max(maxCols, row.getLastCellNum());
            }
        }
        if (maxCols <= 0) {
            return;
        }
        boolean headerRendered = false;
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            sb.append(rowToMarkdown(row, maxCols)).append("\n");
            if (!headerRendered) {
                // 表头分隔行：| --- | --- | ...
                sb.append("|");
                for (int c = 0; c < maxCols; c++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
                headerRendered = true;
            }
        }
    }

    /** 一行转 Markdown 单元格文本（不足列数补空）。 */
    private String rowToMarkdown(Row row, int maxCols) {
        StringBuilder line = new StringBuilder("|");
        for (int c = 0; c < maxCols; c++) {
            String value = "";
            if (row != null) {
                Cell cell = row.getCell(c);
                if (cell != null) {
                    // 竖线转义，避免破坏 Markdown 表格结构
                    value = formatter.formatCellValue(cell).replace("|", "\\|").trim();
                }
            }
            line.append(" ").append(value).append(" |");
        }
        return line.toString();
    }
}
