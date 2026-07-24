package com.richard.fyoung.customeradmin.aiconfig.channelrobot.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotVO;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.service.ChannelRobotService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道机器人管理：CRUD。列表返回 MyBatis-Plus 原生分页体（records/total/current/size），
 * 密文永不下发，仅回 {@code hasSecret} 布尔。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/channel-robots")
public class ChannelRobotController {

    private final ChannelRobotService channelRobotService;

    public ChannelRobotController(ChannelRobotService channelRobotService) {
        this.channelRobotService = channelRobotService;
    }

    @SaCheckPermission("channel-robot:view")
    @GetMapping("/page")
    public Result<IPage<ChannelRobotVO>> page(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size,
                                              @RequestParam(required = false) String channelType,
                                              @RequestParam(required = false) String keyword) {
        return Result.success(channelRobotService.page(current, size, channelType, keyword));
    }

    @SaCheckPermission("channel-robot:add")
    @OperationLog(operation = "新建渠道机器人", target = "ai_channel_robot")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ChannelRobotSaveRequest request) {
        channelRobotService.create(request);
        return Result.success();
    }

    @SaCheckPermission("channel-robot:edit")
    @OperationLog(operation = "编辑渠道机器人", target = "ai_channel_robot")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ChannelRobotSaveRequest request) {
        channelRobotService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("channel-robot:delete")
    @OperationLog(operation = "删除渠道机器人", target = "ai_channel_robot")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        channelRobotService.delete(id);
        return Result.success();
    }
}
