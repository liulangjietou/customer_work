package com.richard.fyoung.customeradmin.ops.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词版本运营入口：版本历史与全文比对。
 *
 * <p>与 B4 的配置版本（{@code /api/config-version}）是两回事：那个记"发布下发了什么"，
 * 这个记"运行时实际生效的是什么"。灰度未覆盖、推送未到达时两者会不一致，
 * 而能跟评测指标对上号的是后者。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ops/prompt-version")
public class PromptVersionController {

    private static final int DEFAULT_LIMIT = 30;

    private final OpsAdminService opsAdminService;

    public PromptVersionController(OpsAdminService opsAdminService) {
        this.opsAdminService = opsAdminService;
    }

    /** 版本历史（观测时间倒序）；列表带全文，前端据此做两版对比。 */
    @SaCheckPermission("prompt-version:view")
    @GetMapping("/list")
    public Result<List<PromptVersion>> list(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return Result.success(opsAdminService.listPromptVersions(limit));
    }

    /** 按指纹取单版全文——评测报告里的 promptFingerprint 就是它，据此定位到具体哪一版。 */
    @SaCheckPermission("prompt-version:view")
    @GetMapping("/{fingerprint}")
    public Result<PromptVersion> detail(@PathVariable String fingerprint) {
        return Result.success(opsAdminService.getPromptVersion(fingerprint));
    }
}
