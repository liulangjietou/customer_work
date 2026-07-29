package com.richard.fyoung.customeradmin.system.devtool.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolKeystoreParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.service.DevToolCertService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 开发者工具箱 · 证书解析。权限复用工具箱菜单的 {@code devtools:view}
 * （能进工具箱页面的人即可使用其中的工具，与其余工具的授权粒度保持一致）。
 *
 * <p>所有输入只在请求内存中解析，不落库、不留痕（含私钥与密钥库密码）。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/devtools/cert")
public class DevToolCertController {

    /** 密钥库上传大小上限：1MB（证书库远小于此，超限基本是传错了文件）。 */
    private static final long MAX_KEYSTORE_BYTES = 1024 * 1024;

    private final DevToolCertService devToolCertService;

    public DevToolCertController(DevToolCertService devToolCertService) {
        this.devToolCertService = devToolCertService;
    }

    /** 解析 PEM 文本中的证书/证书链/CSR。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/parse")
    public Result<DevToolCertParseResponse> parse(@Valid @RequestBody DevToolCertParseRequest request) {
        return Result.success(devToolCertService.parse(request.getPemContent()));
    }

    /** 私钥与证书匹配校验。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/match")
    public Result<DevToolCertMatchResponse> match(@Valid @RequestBody DevToolCertMatchRequest request) {
        return Result.success(devToolCertService.match(request.getCertPem(), request.getPrivateKeyPem()));
    }

    /** 解析 PFX/JKS 密钥库（multipart 上传 + 库密码）。 */
    @SaCheckPermission("devtools:view")
    @PostMapping("/keystore")
    public Result<DevToolKeystoreParseResponse> keystore(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "password", required = false, defaultValue = "") String password) throws IOException {
        if (file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "请上传密钥库文件（.pfx/.p12/.jks）");
        }
        if (file.getSize() > MAX_KEYSTORE_BYTES) {
            throw new BizException(ResultCode.PARAM_INVALID, "密钥库文件过大（上限 1MB）");
        }
        return Result.success(devToolCertService.parseKeystore(file.getBytes(), password));
    }
}
