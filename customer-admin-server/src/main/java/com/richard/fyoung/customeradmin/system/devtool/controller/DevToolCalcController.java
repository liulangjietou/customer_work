package com.richard.fyoung.customeradmin.system.devtool.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCronExplainRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCronExplainResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolFormatConvertRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolFormatConvertResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolJwtDecodeRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolJwtDecodeResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolTextDiffRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolTextDiffResponse;
import com.richard.fyoung.customeradmin.system.devtool.service.DevToolCalcService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发者工具箱 · 纯计算类工具的统一入口：cron 解析、JWT 解析、文本比对、格式互转。
 *
 * <p>这四项与 HTTP 代理（要防 SSRF）、证书解析（走 multipart 且涉及私钥）各有独立的横切关注点不同，
 * 都是无副作用的纯计算，故合在一个 controller 里，不为每项各建一个只有单个接口的类。</p>
 *
 * <p>权限复用工具箱菜单的 {@code devtools:view}，与其余工具的授权粒度保持一致。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/devtools")
public class DevToolCalcController {

    private final DevToolCalcService devToolCalcService;

    public DevToolCalcController(DevToolCalcService devToolCalcService) {
        this.devToolCalcService = devToolCalcService;
    }

    /** 解析 cron 表达式并推算后续执行时间。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/cron/explain")
    public Result<DevToolCronExplainResponse> explainCron(@Valid @RequestBody DevToolCronExplainRequest request) {
        return Result.success(devToolCalcService.explainCron(request));
    }

    /** 解析 JWT，可选校验 HS* 签名。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/jwt/decode")
    public Result<DevToolJwtDecodeResponse> decodeJwt(@Valid @RequestBody DevToolJwtDecodeRequest request) {
        return Result.success(devToolCalcService.decodeJwt(request));
    }

    /** 行级比对两段文本。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/diff/text")
    public Result<DevToolTextDiffResponse> diffText(@Valid @RequestBody DevToolTextDiffRequest request) {
        return Result.success(devToolCalcService.diffText(request));
    }

    /** JSON / YAML / XML 互转。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/format/convert")
    public Result<DevToolFormatConvertResponse> convertFormat(@Valid @RequestBody DevToolFormatConvertRequest request) {
        return Result.success(devToolCalcService.convertFormat(request));
    }
}
