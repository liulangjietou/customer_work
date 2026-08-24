package com.richard.fyoung.customeradmin.configversion.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionPageQuery;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionVO;
import com.richard.fyoung.customeradmin.configversion.dto.GrayReleaseRequest;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernedChangeVO;
import com.richard.fyoung.customeradmin.governance.change.service.GovernedChangeService;
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
    private final GovernedChangeService governedChangeService;
    private final CrossTenantAuthority crossTenantAuthority;

    public ConfigVersionController(ConfigVersionService versionService,
                                   GovernedChangeService governedChangeService,
                                   CrossTenantAuthority crossTenantAuthority) {
        this.versionService = versionService;
        this.governedChangeService = governedChangeService;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @SaCheckPermission("config-version:view")
    @GetMapping("/page")
    public Result<PageResult<ConfigVersionVO>> page(ConfigVersionPageQuery query) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(versionService.page(query));
    }

    /** 版本详情，含结构化脱敏快照（前端据此做两版非敏感配置对比）。 */
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
     * 安全回滚到指定版本。
     *
     * <p>历史只提供提示词与最大迭代次数补丁；模型、凭据、MCP、路由与在线实验从当前权威数据重组。
     * 返回 PENDING 任务不表示已生效，只有实例 ACK APPLIED 才表示真实应用。</p>
     */
    @SaCheckPermission("config-version:rollback")
    @OperationLog(operation = "回滚配置版本", target = "ai_config_version")
    @PostMapping("/{id}/rollback")
    public Result<GovernedChangeVO> rollback(
        @PathVariable Long id, @RequestParam(required = false) String remark) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(governedChangeService.submitRollback(
            id, remark, StpUtil.getLoginIdAsLong(), username()));
    }

    /** 安全灰度：全部目标预校验通过后整批可靠入队，每个租户使用自己的当前凭据与资产。 */
    @SaCheckPermission("config-version:gray")
    @OperationLog(operation = "灰度发布配置", target = "ai_config_version")
    @PostMapping("/{id}/gray")
    public Result<GovernedChangeVO> grayRelease(
        @PathVariable Long id, @Valid @RequestBody GrayReleaseRequest request) {
        crossTenantAuthority.requireCurrentUserAuthority();
        return Result.success(governedChangeService.submitGrayRelease(id, request.getTenantCodes(),
            request.getRemark(), StpUtil.getLoginIdAsLong(), username()));
    }

    private String username() {
        return StpUtil.getTokenSession().getString("username");
    }
}
