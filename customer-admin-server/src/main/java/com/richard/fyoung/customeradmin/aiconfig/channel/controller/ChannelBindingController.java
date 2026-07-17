package com.richard.fyoung.customeradmin.aiconfig.channel.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.dto.ChannelBindingVO;
import com.richard.fyoung.customeradmin.aiconfig.channel.service.ChannelBindingService;
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

import java.util.List;

/**
 * 渠道-客服机器人运行配置绑定管理入口：CRUD + 「重新发布」。
 *
 * <p>复用智能体管理权限（{@code agent:view}/{@code agent:edit}）——绑定是智能体运行配置下发的从属概念，
 * 不单设权限项，避免为一个最小管理入口引入独立菜单/权限种子。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/aiconfig/channel-binding")
public class ChannelBindingController {

    private final ChannelBindingService channelBindingService;

    public ChannelBindingController(ChannelBindingService channelBindingService) {
        this.channelBindingService = channelBindingService;
    }

    @SaCheckPermission("agent:view")
    @GetMapping
    public Result<List<ChannelBindingVO>> list() {
        return Result.success(channelBindingService.list());
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "新建渠道绑定", target = "ai_channel_binding")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody ChannelBindingSaveRequest request) {
        channelBindingService.create(request);
        return Result.success();
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "编辑渠道绑定", target = "ai_channel_binding")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ChannelBindingSaveRequest request) {
        channelBindingService.update(id, request);
        return Result.success();
    }

    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "删除渠道绑定", target = "ai_channel_binding")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        channelBindingService.delete(id);
        return Result.success();
    }

    /** 重新发布：把该渠道绑定的智能体运行时配置重新下发到 8080（强制连通性探测）。 */
    @SaCheckPermission("agent:edit")
    @OperationLog(operation = "重新发布运行时配置", target = "ai_channel_binding")
    @PostMapping("/{channelCode}/republish")
    public Result<String> republish(@PathVariable String channelCode) {
        return Result.success(channelBindingService.republish(channelCode));
    }
}
