package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskPageQuery;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskRunVO;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto.ScheduledTaskVO;
import com.richard.fyoung.customeradmin.aiconfig.scheduledtask.service.ScheduledTaskService;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理：CRUD + 启停 + 手动触发 + 执行历史。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/scheduled-task")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @SaCheckPermission("scheduler:view")
    @GetMapping("/page")
    public Result<IPage<ScheduledTaskVO>> page(ScheduledTaskPageQuery query) {
        return Result.success(scheduledTaskService.page(query));
    }

    @SaCheckPermission("scheduler:view")
    @GetMapping("/{id}")
    public Result<ScheduledTaskVO> get(@PathVariable Long id) {
        return Result.success(scheduledTaskService.get(id));
    }

    @SaCheckPermission("scheduler:add")
    @OperationLog(operation = "新建定时任务", target = "ai_scheduled_task")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ScheduledTaskSaveRequest request) {
        scheduledTaskService.create(request);
        return Result.success();
    }

    @SaCheckPermission("scheduler:edit")
    @OperationLog(operation = "编辑定时任务", target = "ai_scheduled_task")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduledTaskSaveRequest request) {
        scheduledTaskService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("scheduler:delete")
    @OperationLog(operation = "删除定时任务", target = "ai_scheduled_task")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scheduledTaskService.delete(id);
        return Result.success();
    }

    @SaCheckPermission("scheduler:edit")
    @OperationLog(operation = "启用定时任务", target = "ai_scheduled_task")
    @PutMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        scheduledTaskService.enable(id);
        return Result.success();
    }

    @SaCheckPermission("scheduler:edit")
    @OperationLog(operation = "停用定时任务", target = "ai_scheduled_task")
    @PutMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        scheduledTaskService.disable(id);
        return Result.success();
    }

    /** 手动触发：同步执行（耗时可能明显长于普通接口），执行完成后返回本次执行历史记录。 */
    @SaCheckPermission("scheduler:trigger")
    @OperationLog(operation = "手动触发定时任务", target = "ai_scheduled_task")
    @PostMapping("/{id}/trigger")
    public Result<ScheduledTaskRunVO> trigger(@PathVariable Long id) {
        return Result.success(scheduledTaskService.trigger(id));
    }

    @SaCheckPermission("scheduler:view")
    @GetMapping("/{id}/runs")
    public Result<IPage<ScheduledTaskRunVO>> runs(@PathVariable Long id, ScheduledTaskPageQuery query) {
        return Result.success(scheduledTaskService.runs(id, query));
    }
}
