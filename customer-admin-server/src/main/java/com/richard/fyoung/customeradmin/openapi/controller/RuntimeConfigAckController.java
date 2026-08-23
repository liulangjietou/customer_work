package com.richard.fyoung.customeradmin.openapi.controller;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.openapi.OpenApiWebConfig;
import com.richard.fyoung.customeradmin.openapi.RuntimeConfigAckAuthInterceptor;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 客服实例运行时配置应用回执入口，鉴权、租户与实例绑定由 ACK 专用拦截器完成。 */
@RestController
@RequestMapping(OpenApiWebConfig.RUNTIME_CONFIG_ACK_PATH)
public class RuntimeConfigAckController {

    private final RuntimePublishTaskService taskService;

    public RuntimeConfigAckController(RuntimePublishTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public Result<Map<String, String>> acknowledge(@RequestBody RuntimeConfigAck ack,
                                                   HttpServletRequest request) {
        Object authenticatedInstance = request.getAttribute(
            RuntimeConfigAckAuthInterceptor.AUTHENTICATED_INSTANCE_ATTRIBUTE);
        if (!(authenticatedInstance instanceof String instanceId)) {
            throw new IllegalArgumentException("runtime config ACK instance identity is missing");
        }
        RuntimePublishStatus status = taskService.recordAck(ack, instanceId);
        return Result.success(Map.of("status", status.name()));
    }
}
