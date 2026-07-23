package com.richard.fyoung.customeradmin.system.devtool.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendRequest;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolHttpSendResponse;
import com.richard.fyoung.customeradmin.system.devtool.service.DevToolHttpService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发者工具箱 · HTTP 请求工具后端代理。权限复用工具箱菜单的 {@code devtools:view}
 * （能进工具箱页面的人即可使用其中的工具，与其余工具的授权粒度保持一致）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/devtools/http")
public class DevToolHttpController {

    private final DevToolHttpService devToolHttpService;

    public DevToolHttpController(DevToolHttpService devToolHttpService) {
        this.devToolHttpService = devToolHttpService;
    }

    @SaCheckPermission("devtools:view")
    @PostMapping("/send")
    public Result<DevToolHttpSendResponse> send(@Valid @RequestBody DevToolHttpSendRequest request) {
        return Result.success(devToolHttpService.send(request));
    }
}
