package com.richard.fyoung.customeradmin.workspace.project.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.workspace.project.dto.AddSessionRequest;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectSaveRequest;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectSessionVO;
import com.richard.fyoung.customeradmin.workspace.project.dto.ProjectVO;
import com.richard.fyoung.customeradmin.workspace.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Projects：跨智能体会话分组管理（一级菜单，权限点复用 workspace，见 V11 迁移注释）。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/workspace/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @SaCheckPermission("workspace")
    @GetMapping
    public Result<List<ProjectVO>> list(@RequestParam(required = false) String keyword) {
        return Result.success(projectService.list(keyword, StpUtil.getLoginIdAsLong()));
    }

    @SaCheckPermission("workspace")
    @OperationLog(operation = "新建项目", target = "ai_project")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ProjectSaveRequest request) {
        projectService.create(request);
        return Result.success();
    }

    @SaCheckPermission("workspace")
    @OperationLog(operation = "编辑项目", target = "ai_project")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProjectSaveRequest request) {
        projectService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("workspace")
    @OperationLog(operation = "删除项目", target = "ai_project")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("workspace")
    @GetMapping("/{id}/sessions")
    public Result<List<ProjectSessionVO>> listSessions(@PathVariable Long id) {
        return Result.success(projectService.listSessions(id, StpUtil.getLoginIdAsLong()));
    }

    @SaCheckPermission("workspace")
    @OperationLog(operation = "会话加入项目", target = "ai_project_session")
    @PostMapping("/{id}/sessions")
    public Result<Void> addSession(@PathVariable Long id, @Valid @RequestBody AddSessionRequest request) {
        projectService.addSession(id, request, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @SaCheckPermission("workspace")
    @OperationLog(operation = "会话移出项目", target = "ai_project_session")
    @DeleteMapping("/{id}/sessions/{agentCode}/{sessionId}")
    public Result<Void> removeSession(@PathVariable Long id, @PathVariable String agentCode, @PathVariable String sessionId) {
        projectService.removeSession(id, agentCode, sessionId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
