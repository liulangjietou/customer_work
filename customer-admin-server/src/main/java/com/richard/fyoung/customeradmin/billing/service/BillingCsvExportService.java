package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.BillingCsvFile;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** 按页面账单口径生成可直接下载的 UTF-8 CSV。 */
@Service
public class BillingCsvExportService {

    private static final String UTF8_BOM = "\uFEFF";
    private static final String CURRENCY = "CNY";

    private final BillingReportService reportService;

    public BillingCsvExportService(BillingReportService reportService) {
        this.reportService = reportService;
    }

    public BillingCsvFile exportTenant(String tenantId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<UsageAggregate> rows = reportService.tenantBill(tenantId, from, to);
        return file("billing-" + tenantId + "-" + from + "-" + to + ".csv", rows);
    }

    public BillingCsvFile exportPlatform(LocalDate from, LocalDate to) {
        validateRange(from, to);
        List<UsageAggregate> rows = reportService.platformOverview(from, to);
        return file("billing-platform-" + from + "-" + to + ".csv", rows);
    }

    private BillingCsvFile file(String filename, List<UsageAggregate> rows) {
        StringBuilder csv = new StringBuilder(UTF8_BOM)
            .append("tenant_id,provider,model_name,call_count,input_tokens,output_tokens,cached_tokens,total_tokens,amount,currency\r\n");
        for (UsageAggregate row : rows) {
            csv.append(cell(row.getTenantId())).append(',')
                .append(cell(row.getProvider())).append(',')
                .append(cell(row.getModelName())).append(',')
                .append(number(row.getCallCount())).append(',')
                .append(number(row.getInputTokens())).append(',')
                .append(number(row.getOutputTokens())).append(',')
                .append(number(row.getCachedTokens())).append(',')
                .append(number(row.getTotalTokens())).append(',')
                .append(row.getAmount() == null ? "0" : row.getAmount().toPlainString()).append(',')
                .append(CURRENCY)
                .append("\r\n");
        }
        return new BillingCsvFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 防止模型名等外部文本在 Excel 中被当作公式执行。 */
    private String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = startsFormula(value) ? "'" + value : value;
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
            || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private boolean startsFormula(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
            || first == '\t' || first == '\r';
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BizException(ResultCode.PARAM_INVALID, "账单日期区间不合法");
        }
    }
}
