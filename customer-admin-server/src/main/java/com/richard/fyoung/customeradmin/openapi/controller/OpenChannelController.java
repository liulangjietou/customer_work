package com.richard.fyoung.customeradmin.openapi.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.openapi.dto.ChannelSessionRequest;
import com.richard.fyoung.customeradmin.openapi.dto.OpenChannelRobotVO;
import com.richard.fyoung.customeradmin.openapi.service.OpenChannelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 开放 API - 渠道配置与会话：供 customer-channel 模块拉取机器人配置、解析/重置外部用户会话。
 * 鉴权由 {@code OpenApiAuthInterceptor} 统一在 {@code /api/open/**} 前置完成。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/open/channel")
public class OpenChannelController {

    private static final String KEY_SESSION_ID = "sessionId";

    private final OpenChannelService openChannelService;

    public OpenChannelController(OpenChannelService openChannelService) {
        this.openChannelService = openChannelService;
    }

    /** 启用的渠道机器人列表（含解密 appSecret + version），可选按 channelType 过滤。 */
    @GetMapping("/robots")
    public Result<List<OpenChannelRobotVO>> robots(@RequestParam(required = false) String channelType) {
        return Result.success(openChannelService.listRobots(channelType));
    }

    /** 解析外部用户会话：无则创建，返回 {sessionId}。 */
    @PostMapping("/sessions/resolve")
    public Result<Map<String, String>> resolveSession(@Valid @RequestBody ChannelSessionRequest request) {
        String sessionId = openChannelService.resolveSession(
            request.channelType(), request.appKey(), request.externalUserId());
        return Result.success(Map.of(KEY_SESSION_ID, sessionId));
    }

    /** 重置外部用户会话：生成新 sessionId 覆盖（无记录等同 resolve），返回 {sessionId}。 */
    @PostMapping("/sessions/reset")
    public Result<Map<String, String>> resetSession(@Valid @RequestBody ChannelSessionRequest request) {
        String sessionId = openChannelService.resetSession(
            request.channelType(), request.appKey(), request.externalUserId());
        return Result.success(Map.of(KEY_SESSION_ID, sessionId));
    }
}
