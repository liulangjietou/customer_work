package com.richard.fyoung.customeradmin.menu.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.menu.dto.MenuNode;
import com.richard.fyoung.customeradmin.menu.service.MenuAggregationService;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态菜单聚合端点：静态菜单（按当前用户权限点过滤）+ 启用中的智能体动态节点。
 * {@code GET /api/menu/version} 供前端轻量轮询，仅版本号变化时才拉全量菜单树。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuAggregationService menuAggregationService;
    private final MenuVersionHolder menuVersionHolder;

    public MenuController(MenuAggregationService menuAggregationService, MenuVersionHolder menuVersionHolder) {
        this.menuAggregationService = menuAggregationService;
        this.menuVersionHolder = menuVersionHolder;
    }

    @GetMapping("/routes")
    public Result<List<MenuNode>> routes() {
        return Result.success(menuAggregationService.buildMenuTree());
    }

    @GetMapping("/version")
    public Result<Long> version() {
        return Result.success(menuVersionHolder.current());
    }
}
