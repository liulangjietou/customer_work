package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.alibaba.excel.EasyExcel;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 动态列查询结果导出 xlsx：表头取结果列名（保序），数据按列序取值。
 *
 * <p>从 Controller 抽出为独立工具，让单测能真实走到 POI 工作簿创建——EasyExcel 与 starter
 * 传递的 poi-ooxml 存在版本对齐要求（见 pom 注释），纯 CRUD 测试覆盖不到该类加载路径，
 * 必须有用例实际生成一次 xlsx 才能在构建期拦住 classpath 分裂问题。</p>
 * @author owlzhangfq@gmail.com
 */
public final class XlsxExporter {

    private XlsxExporter() {
    }

    /** 生成 xlsx 字节流；列为空时导出仅含空 sheet 的合法文件。 */
    public static byte[] write(SqlQueryResultVO result) {
        List<String> columns = result.getColumns() == null ? Collections.emptyList() : result.getColumns();
        List<List<String>> head = new ArrayList<>(columns.size());
        for (String column : columns) {
            head.add(Collections.singletonList(column));
        }
        List<List<Object>> data = new ArrayList<>();
        if (result.getRows() != null) {
            for (Map<String, Object> row : result.getRows()) {
                List<Object> line = new ArrayList<>(columns.size());
                for (String column : columns) {
                    line.add(row.get(column));
                }
                data.add(line);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet("data").doWrite(data);
        return out.toByteArray();
    }
}
