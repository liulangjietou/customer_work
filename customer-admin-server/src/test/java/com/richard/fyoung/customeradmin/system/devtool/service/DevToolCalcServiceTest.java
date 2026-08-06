package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCronExplainRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCronExplainResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolFormatConvertRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolFormatConvertResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolJwtDecodeRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolJwtDecodeResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolTextDiffRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolTextDiffResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DevToolCalcService} 单测。
 *
 * <p>本类只负责 <b>VO 转换</b>与<b>异常转换</b>——四项算法本身已下沉到 starter 的
 * {@code CronDevToolOps} / {@code JwtDevToolOps} / {@code DiffDevToolOps} / {@code DataFormatDevToolOps}，
 * 其正确性由对应的 Ops 单测覆盖，这里不重复验证，只确认字段没漏映射、
 * {@link IllegalArgumentException} 被翻译成 {@link ResultCode#PARAM_INVALID} 而非裸穿到 Controller。</p>
 * @author owlzhangfq@gmail.com
 */
class DevToolCalcServiceTest {

    /** jwt.io 公开示例令牌（HS256，密钥 your-256-bit-secret），不含任何真实凭据。 */
    private static final String SAMPLE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
        + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    private final DevToolCalcService service = new DevToolCalcService();

    // ---------- cron ----------

    @Test
    void explainCron_shouldMapAllFields() {
        DevToolCronExplainRequest request = new DevToolCronExplainRequest();
        request.setExpression("0 0 2 * * ?");
        request.setCount(3);

        DevToolCronExplainResponse response = service.explainCron(request);
        assertEquals("0 0 2 * * ?", response.getExpression());
        assertEquals("Asia/Shanghai", response.getTimezone());
        assertEquals(6, response.getFields().size());
        assertEquals(3, response.getNextTimes().size());
        DevToolCronExplainResponse.Field first = response.getFields().get(0);
        assertEquals("秒", first.getName());
        assertEquals("0", first.getValue());
        assertNotNull(first.getRange());
        assertNotNull(first.getDescription());
    }

    @Test
    void explainCron_shouldTranslateIllegalArgumentToBizException() {
        DevToolCronExplainRequest request = new DevToolCronExplainRequest();
        request.setExpression("0 2 * * *");

        BizException ex = assertThrows(BizException.class, () -> service.explainCron(request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
        assertTrue(ex.getMessage().contains("5 段"), "错误原因应透传到页面，实际：" + ex.getMessage());
    }

    // ---------- JWT ----------

    @Test
    void decodeJwt_shouldMapAllFields() {
        DevToolJwtDecodeRequest request = new DevToolJwtDecodeRequest();
        request.setToken(SAMPLE_TOKEN);
        request.setSecret("your-256-bit-secret");

        DevToolJwtDecodeResponse response = service.decodeJwt(request);
        assertEquals("HS256", response.getAlgorithm());
        assertEquals("JWT", response.getType());
        assertEquals("1234567890", response.getSubject());
        assertEquals("VALID", response.getSignatureStatus());
        assertNotNull(response.getHeader());
        assertNotNull(response.getPayload());
        assertEquals("2018-01-18 09:30:22", response.getIssuedAt());
        assertFalse(response.isExpired());
        assertFalse(response.isUnsigned());
    }

    @Test
    void decodeJwt_shouldReportNotChecked_whenSecretMissing() {
        DevToolJwtDecodeRequest request = new DevToolJwtDecodeRequest();
        request.setToken(SAMPLE_TOKEN);

        assertEquals("NOT_CHECKED", service.decodeJwt(request).getSignatureStatus());
    }

    @Test
    void decodeJwt_shouldTranslateIllegalArgumentToBizException() {
        DevToolJwtDecodeRequest request = new DevToolJwtDecodeRequest();
        request.setToken("not-a-jwt");

        BizException ex = assertThrows(BizException.class, () -> service.decodeJwt(request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    // ---------- 文本比对 ----------

    @Test
    void diffText_shouldMapLinesAndCounters() {
        DevToolTextDiffRequest request = new DevToolTextDiffRequest();
        request.setOldText("a\nb\nc");
        request.setNewText("a\nB\nc\nd");

        DevToolTextDiffResponse response = service.diffText(request);
        assertFalse(response.isIdentical());
        assertEquals(2, response.getAddedLines());
        assertEquals(1, response.getDeletedLines());
        assertFalse(response.isTruncated());
        assertEquals(response.getTotalLines(), response.getLines().size());
        DevToolTextDiffResponse.Line line = response.getLines().get(0);
        assertEquals("EQUAL", line.getType());
        assertEquals(1, line.getOldLineNo());
        assertEquals(1, line.getNewLineNo());
        assertEquals("a", line.getContent());
    }

    @Test
    void diffText_shouldPassThroughIgnoreOptions() {
        DevToolTextDiffRequest request = new DevToolTextDiffRequest();
        request.setOldText("A\n  b");
        request.setNewText("a\nb");
        request.setIgnoreCase(true);
        request.setIgnoreWhitespace(true);

        assertTrue(service.diffText(request).isIdentical(), "两个忽略选项都应透传到 Ops");
    }

    @Test
    void diffText_shouldTranslateIllegalArgumentToBizException() {
        DevToolTextDiffRequest request = new DevToolTextDiffRequest();
        request.setOldText("x\n".repeat(1501));
        request.setNewText("y");

        BizException ex = assertThrows(BizException.class, () -> service.diffText(request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    // ---------- 格式互转 ----------

    @Test
    void convertFormat_shouldMapResult() {
        DevToolFormatConvertRequest request = new DevToolFormatConvertRequest();
        request.setContent("{\"a\":1}");
        request.setSourceFormat("json");
        request.setTargetFormat("yaml");

        DevToolFormatConvertResponse response = service.convertFormat(request);
        assertEquals("json", response.getSourceFormat());
        assertEquals("yaml", response.getTargetFormat());
        assertTrue(response.getResult().contains("a: 1"));
    }

    @Test
    void convertFormat_shouldPassThroughRootName() {
        DevToolFormatConvertRequest request = new DevToolFormatConvertRequest();
        request.setContent("{\"a\":1}");
        request.setSourceFormat("json");
        request.setTargetFormat("xml");
        request.setRootName("order");

        assertTrue(service.convertFormat(request).getResult().startsWith("<order>"));
    }

    @Test
    void convertFormat_shouldTranslateIllegalArgumentToBizException() {
        DevToolFormatConvertRequest request = new DevToolFormatConvertRequest();
        request.setContent("{\"a\":1}");
        request.setSourceFormat("json");
        request.setTargetFormat("toml");

        BizException ex = assertThrows(BizException.class, () -> service.convertFormat(request));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }
}
