package com.richard.fyoung.customerwork.sqlkit;

import com.alibaba.excel.EasyExcel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 动态列查询结果导出 xlsx：表头取结果列名（保序），数据按列序取值。
 *
 * <p>入参用「列名列表 + 行 Map 列表」而非某个查询结果 VO，与调用方的 DTO 解耦。</p>
 *
 * <p>抽成独立工具是为了让单测能真实走到 POI 工作簿创建——EasyExcel 与 agentscope-extensions-rag-simple
 * 传递的 poi-ooxml 存在版本对齐要求（见 pom 注释），纯 CRUD 测试覆盖不到该类加载路径，
 * 必须有用例实际生成一次 xlsx 才能在构建期拦住 classpath 分裂问题。</p>
 * @author owlzhangfq@gmail.com
 */
public final class XlsxExporter {

    private XlsxExporter() {
    }

    /**
     * 生成 xlsx 字节流；列为空时导出仅含空 sheet 的合法文件。
     *
     * @param columns 列名（决定表头与取值顺序）
     * @param rows    行数据（列名 → 值），列名缺失的单元格留空
     */
    public static byte[] write(List<String> columns, List<Map<String, Object>> rows) {
        List<String> safeColumns = columns == null ? Collections.emptyList() : columns;
        List<List<String>> head = new ArrayList<>(safeColumns.size());
        for (String column : safeColumns) {
            head.add(Collections.singletonList(column));
        }
        List<List<Object>> data = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                List<Object> line = new ArrayList<>(safeColumns.size());
                for (String column : safeColumns) {
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
