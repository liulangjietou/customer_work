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
import com.richard.fyoung.customerwork.devtool.CronDevToolOps;
import com.richard.fyoung.customerwork.devtool.DataFormatDevToolOps;
import com.richard.fyoung.customerwork.devtool.DiffDevToolOps;
import com.richard.fyoung.customerwork.devtool.JwtDevToolOps;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 开发者工具箱 · 纯计算类工具（cron 解析 / JWT 解析 / 文本比对 / 格式互转）的页面侧入口。
 *
 * <p><b>算法不在本类</b>：四项能力的实现都在 starter 的 Ops（纯函数、无 Spring 依赖），同一份实现
 * 同时由 {@code devtoolbox} 系统工具的 {@code cron_explain} / {@code jwt_decode} / {@code text_diff} /
 * {@code data_convert} 暴露给智能体。页面与智能体共用一套逻辑是刻意为之——工具箱早期把 JSON、
 * 编解码等能力在前端各实现了一遍，结果两侧能力集悄悄分叉（AES 模式互不包含即是一例），这批新工具
 * 一律只留后端一套。本类只做 DTO 转换与异常翻译。</p>
 *
 * <p>cron 尤其不能放到前端算：表达式最终由 XXL-JOB 按 Quartz 6 段语义触发，浏览器端 cron 库多按
 * Unix 5 段解析，同一串表达式两边给出的"下次执行时间"可能不同，那样的工具会误导排查。</p>
 *
 * <p>隐私边界：JWT 与其密钥只在请求内存中解析，不落库、不写日志。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DevToolCalcService {

    private final CronDevToolOps cronOps = new CronDevToolOps();
    private final JwtDevToolOps jwtOps = new JwtDevToolOps();
    private final DiffDevToolOps diffOps = new DiffDevToolOps();
    private final DataFormatDevToolOps dataFormatOps = new DataFormatDevToolOps();

    /** 解析 cron：校验、逐字段释义、推算后续执行时间。 */
    public DevToolCronExplainResponse explainCron(DevToolCronExplainRequest request) {
        CronDevToolOps.CronExplainResult result = call(() ->
            cronOps.explain(request.getExpression(), request.getCount(), request.getTimezone()));

        List<DevToolCronExplainResponse.Field> fields = new ArrayList<>(result.getFields().size());
        for (CronDevToolOps.CronFieldDesc desc : result.getFields()) {
            fields.add(new DevToolCronExplainResponse.Field(
                desc.getName(), desc.getValue(), desc.getRange(), desc.getDescription()));
        }
        DevToolCronExplainResponse response = new DevToolCronExplainResponse();
        response.setExpression(result.getExpression());
        response.setTimezone(result.getTimezone());
        response.setFields(fields);
        response.setNextTimes(result.getNextTimes());
        return response;
    }

    /** 解析 JWT：拆解 header/payload、解读有效期，可选 HS* 验签。 */
    public DevToolJwtDecodeResponse decodeJwt(DevToolJwtDecodeRequest request) {
        JwtDevToolOps.JwtDecodeResult result = call(() ->
            jwtOps.decode(request.getToken(), request.getSecret(), request.getSecretEncoding()));

        DevToolJwtDecodeResponse response = new DevToolJwtDecodeResponse();
        response.setAlgorithm(result.getAlgorithm());
        response.setType(result.getType());
        response.setHeader(result.getHeader());
        response.setPayload(result.getPayload());
        response.setIssuer(result.getIssuer());
        response.setSubject(result.getSubject());
        response.setAudience(result.getAudience());
        response.setJwtId(result.getJwtId());
        response.setIssuedAt(result.getIssuedAt());
        response.setNotBefore(result.getNotBefore());
        response.setExpiresAt(result.getExpiresAt());
        response.setExpired(result.isExpired());
        response.setNotYetValid(result.isNotYetValid());
        response.setSecondsRemaining(result.getSecondsRemaining());
        response.setUnsigned(result.isUnsigned());
        response.setSignatureStatus(result.getSignatureStatus());
        return response;
    }

    /** 行级文本比对。 */
    public DevToolTextDiffResponse diffText(DevToolTextDiffRequest request) {
        DiffDevToolOps.DiffResult result = call(() -> diffOps.diff(
            request.getOldText(), request.getNewText(),
            request.getIgnoreWhitespace(), request.getIgnoreCase()));

        List<DevToolTextDiffResponse.Line> lines = new ArrayList<>(result.getLines().size());
        for (DiffDevToolOps.DiffLine line : result.getLines()) {
            lines.add(new DevToolTextDiffResponse.Line(
                line.getType(), line.getOldLineNo(), line.getNewLineNo(), line.getContent()));
        }
        DevToolTextDiffResponse response = new DevToolTextDiffResponse();
        response.setIdentical(result.isIdentical());
        response.setAddedLines(result.getAddedLines());
        response.setDeletedLines(result.getDeletedLines());
        response.setTotalLines(result.getTotalLines());
        response.setTruncated(result.isTruncated());
        response.setLines(lines);
        return response;
    }

    /** JSON / YAML / XML 互转。 */
    public DevToolFormatConvertResponse convertFormat(DevToolFormatConvertRequest request) {
        DataFormatDevToolOps.ConvertResult result = call(() -> dataFormatOps.convert(
            request.getContent(), request.getSourceFormat(), request.getTargetFormat(), request.getRootName()));
        return new DevToolFormatConvertResponse(
            result.getSourceFormat(), result.getTargetFormat(), result.getResult());
    }

    /** Ops 的入参校验一律抛 IllegalArgumentException，在此统一翻译成带原因的业务异常。 */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
        }
    }
}
