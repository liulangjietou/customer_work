package com.richard.fyoung.customeradmin.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.ticket.dto.CancelOrderRequest;
import com.richard.fyoung.customeradmin.ticket.dto.ModifyAddressRequest;
import com.richard.fyoung.customeradmin.ticket.dto.OrderDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageResult;
import com.richard.fyoung.customeradmin.ticket.service.UserOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户订单（人工客服后台）：坐席订单列表/详情查看 + 改址/取消，全部通过 {@link UserOrderService}
 * 代理到 8080。Controller 只做参数编排与权限/操作日志埋点，坐席身份由 RestClient 拦截器自动带上
 * X-Agent-Token；8080 返回的 404/409 由客户端翻译为 BizException，经 GlobalExceptionHandler 转标准响应体。
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/ticket/orders")
public class UserOrderController {

    private static final String TARGET = "user_order";

    private final UserOrderService orderService;

    public UserOrderController(UserOrderService orderService) {
        this.orderService = orderService;
    }

    @SaCheckPermission("user-order:view")
    @GetMapping("/page")
    public Result<OrderPageResult> page(OrderPageQuery query) {
        return Result.success(orderService.page(query));
    }

    @SaCheckPermission("user-order:view")
    @GetMapping("/{orderId}")
    public Result<OrderDetailVO> detail(@PathVariable String orderId) {
        return Result.success(orderService.detail(orderId));
    }

    @SaCheckPermission("user-order:edit")
    @OperationLog(operation = "修改订单收货地址", target = TARGET)
    @PostMapping("/{orderId}/modify-address")
    public Result<Void> modifyAddress(@PathVariable String orderId,
                                      @Valid @RequestBody ModifyAddressRequest request) {
        orderService.modifyAddress(orderId, request.newAddress());
        return Result.success();
    }

    @SaCheckPermission("user-order:edit")
    @OperationLog(operation = "取消订单", target = TARGET)
    @PostMapping("/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable String orderId,
                               @RequestBody(required = false) CancelOrderRequest request) {
        orderService.cancel(orderId, request == null ? null : request.reason());
        return Result.success();
    }
}
