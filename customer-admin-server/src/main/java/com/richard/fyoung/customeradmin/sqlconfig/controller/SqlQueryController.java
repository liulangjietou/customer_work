package com.richard.fyoung.customeradmin.sqlconfig.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.excel.EasyExcel;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryMetaVO;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryRequest;
import com.richard.fyoung.customeradmin.sqlconfig.dto.SqlQueryResultVO;
import com.richard.fyoung.customeradmin.sqlconfig.engine.SqlQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 通用 SQL 查询：按 defineKey 取参数元数据、执行查询、导出 xlsx。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/sql/query")
public class SqlQueryController {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SqlQueryService queryService;

    public SqlQueryController(SqlQueryService queryService) {
        this.queryService = queryService;
    }

    @SaCheckPermission("sql-query:view")
    @GetMapping("/meta")
    public Result<SqlQueryMetaVO> meta(@RequestParam String defineKey) {
        return Result.success(queryService.meta(defineKey));
    }

    @SaCheckPermission("sql-query:view")
    @PostMapping("/execute")
    public Result<SqlQueryResultVO> execute(@Valid @RequestBody SqlQueryRequest request) {
        return Result.success(queryService.execute(request.defineKey(), request.params()));
    }

    @SaCheckPermission("sql-query:export")
    @OperationLog(operation = "导出SQL查询结果", target = "sql_define")
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@Valid @RequestBody SqlQueryRequest request) {
        SqlQueryResultVO result = queryService.executeForExport(request.defineKey(), request.params());
        byte[] content = toXlsx(result);
        String fileName = request.defineKey() + "-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".xlsx";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(content);
    }

    /** 动态列 xlsx：表头取查询结果列名（保序），数据按列序取值。 */
    private byte[] toXlsx(SqlQueryResultVO result) {
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
