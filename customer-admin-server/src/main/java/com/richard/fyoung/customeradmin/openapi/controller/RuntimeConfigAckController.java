package com.richard.fyoung.customeradmin.openapi.controller;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 客服实例运行时配置应用回执入口，鉴权与租户绑定由 Open API 拦截器完成。 */
@RestController
@RequestMapping("/api/open/runtime-config/acks")
public class RuntimeConfigAckController {

    private final RuntimePublishTaskService taskService;

    public RuntimeConfigAckController(RuntimePublishTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public Result<Map<String, String>> acknowledge(@RequestBody RuntimeConfigAck ack) {
        RuntimePublishStatus status = taskService.recordAck(ack);
        return Result.success(Map.of("status", status.name()));
    }
}
