package com.richard.fyoung.customeradmin.message.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.message.dto.SiteMessageVO;
import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息中心：当前登录用户的消息分页/未读数/标记已读。登录即可访问，不新增权限点。
 * 当前用户统一取自 Sa-Token 登录态。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/message")
public class SiteMessageController {

    private final SiteMessageService siteMessageService;

    public SiteMessageController(SiteMessageService siteMessageService) {
        this.siteMessageService = siteMessageService;
    }

    /** 分页查询当前用户站内消息：readFlag 可选（不传查全部），未读优先、时间倒序。 */
    @GetMapping("/page")
    public Result<PageResult<SiteMessageVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer readFlag) {
        return Result.success(siteMessageService.page(StpUtil.getLoginIdAsLong(), readFlag, page, size));
    }

    /** 当前用户未读消息数（前端消息角标）。 */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(siteMessageService.unreadCount(StpUtil.getLoginIdAsLong()));
    }

    /** 标记单条消息已读（校验归属，非本人快速失败）。 */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        siteMessageService.markRead(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /** 标记当前用户全部未读消息为已读。 */
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        siteMessageService.markAllRead(StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
