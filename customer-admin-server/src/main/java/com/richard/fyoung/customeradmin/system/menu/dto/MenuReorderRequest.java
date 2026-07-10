package com.richard.fyoung.customeradmin.system.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 拖拽排序请求：前端把受影响层级（源层级+目标层级）的完整新顺序一次性提交，
 * 后端按 id 逐条更新 parentId/sort，不做增量 diff（拖拽影响面通常就是几个兄弟节点，简单可靠）。
 * @author owlzhangfq@gmail.com
 */
public record MenuReorderRequest(@NotEmpty(message = "items 不能为空") @Valid List<Item> items) {

    public record Item(
        @NotNull(message = "id 不能为空") Long id,
        @NotNull(message = "parentId 不能为空") Long parentId,
        @NotNull(message = "sort 不能为空") Integer sort) {
    }
}
