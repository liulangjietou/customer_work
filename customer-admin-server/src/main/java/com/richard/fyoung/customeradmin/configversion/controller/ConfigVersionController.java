package com.richard.fyoung.customeradmin.configversion.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionPageQuery;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionVO;
import com.richard.fyoung.customeradmin.configversion.dto.GrayReleaseRequest;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.service.ConfigRollbackService;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配置版本：历史查看、版本对比、回滚、灰度发布。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/config-version")
public class ConfigVersionController {

    private final ConfigVersionService versionService;
    private final ConfigRollbackService rollbackService;
    private final CrossTenantAuthority crossTenantAuthority;

    public ConfigVersionController(ConfigVersionService versionService,
                                   ConfigRollbackService rollbackService,
                                   CrossTenantAuthority crossTenantAuthority) {
        this.versionService = versionService;
        this.rollbackService = rollbackService;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @SaCheckPermission("config-version:view")
    @GetMapping("/page")
    public Result<PageResult<ConfigVersionVO>> page(ConfigVersionPageQuery query) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(versionService.page(query));
    }

    /** 版本详情，含完整快照内容（前端据此做两版对比）。 */
    @SaCheckPermission("config-version:view")
    @GetMapping("/{id}")
    public Result<ConfigVersionVO> detail(@PathVariable Long id) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(versionService.detail(id));
    }

    /** 某目标的全部版本（版本选择下拉用）。 */
    @SaCheckPermission("config-version:view")
    @GetMapping("/list")
    public Result<List<ConfigVersionVO>> listByTarget(@RequestParam String configType,
                                                      @RequestParam String targetCode) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(versionService.listByTarget(ConfigType.parse(configType), targetCode));
    }

    /**
     * 回滚到指定版本。
     *
     * <p>回滚会产生一个<b>新版本</b>（内容取自目标版本），而不是删掉后续版本——
     * 发布历史只增不删，任何时刻都能回答"当时线上是哪一版"，回滚本身也可被再回滚。</p>
     */
    @SaCheckPermission("config-version:rollback")
    @OperationLog(operation = "回滚配置版本", target = "ai_config_version")
    @PostMapping("/{id}/rollback")
    public Result<Integer> rollback(@PathVariable Long id,
                                    @RequestParam(required = false) String remark) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(rollbackService.rollback(id, remark));
    }

    /** 灰度发布：把指定版本只下发给名单内的租户。 */
    @SaCheckPermission("config-version:gray")
    @OperationLog(operation = "灰度发布配置", target = "ai_config_version")
    @PostMapping("/{id}/gray")
    public Result<Integer> grayRelease(@PathVariable Long id,
                                       @Valid @RequestBody GrayReleaseRequest request) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(rollbackService.grayRelease(id, request.getTenantCodes(), request.getRemark()));
    }
}
