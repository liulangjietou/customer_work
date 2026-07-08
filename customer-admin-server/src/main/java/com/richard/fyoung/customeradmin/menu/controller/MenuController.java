package com.richard.fyoung.customeradmin.menu.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.menu.dto.MenuNode;
import com.richard.fyoung.customeradmin.menu.service.MenuAggregationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态菜单聚合端点。当前仅返回静态菜单（按当前用户权限点过滤）；动态智能体节点与
 * {@code GET /api/menu/version} 版本号接口在批次三接入。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuAggregationService menuAggregationService;

    public MenuController(MenuAggregationService menuAggregationService) {
        this.menuAggregationService = menuAggregationService;
    }

    @GetMapping("/routes")
    public Result<List<MenuNode>> routes() {
        return Result.success(menuAggregationService.buildMenuTree());
    }
}
