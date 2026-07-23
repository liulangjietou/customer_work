package com.richard.fyoung.customeradmin.system.loginimage.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 登录页轮播图排序请求：前端传调整后的完整 id 顺序，后端按下标重写 sortOrder。
 * @author owlzhangfq@gmail.com
 */
public record LoginImageReorderRequest(@NotEmpty(message = "排序 id 列表不能为空") List<Long> ids) {
}
