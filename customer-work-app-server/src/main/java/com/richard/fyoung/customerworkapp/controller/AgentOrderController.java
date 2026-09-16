package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryQuery;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryRow;
import com.richard.fyoung.customerwork.data.order.OrderDirectoryService;
import com.richard.fyoung.customerwork.data.order.OrderMutationResult;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential.AgentIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 坐席侧订单端点（{@code /api/customer/agent/orders}，X-Agent-Token 鉴权）。
 *
 * <p>坐席身份由 {@code AgentAuthWebFilter} 校验（{@code /api/customer/agent/**} 全覆盖）。订单查询/改址/取消
 * 全部薄委托给 starter 的 {@link OrderDirectoryService}：分页多维查询（含用户名 JOIN）、详情、改址、取消
 * （仅未发货可取消）。订单数据源未启用（mode!=jdbc）时返回 503；订单不存在 404；取消状态不允许 409。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/agent/orders")
@Tag(name = "坐席订单", description = "订单多维查询 / 详情 / 改址 / 取消")
public class AgentOrderController {

    private static final Logger log = LoggerFactory.getLogger(AgentOrderController.class);
    private static final String ERROR_REQUEST = "AGENT-ORDER-REQUEST-FAIL";
    private final OrderDirectoryService orderDirectoryService;

    public AgentOrderController(OrderDirectoryService orderDirectoryService) {
        this.orderDirectoryService = orderDirectoryService;
    }

    /** 改址请求体。 */
    public record ModifyAddressRequest(String newAddress) {
    }

    /** 取消请求体。 */
    public record CancelRequest(String reason) {
    }

    /** 订单列表项（对外契约：金额为两位小数字符串）。 */
    public record AgentOrderVO(String orderId, String userId, String username, String productId,
                               String productName, String amount, String status, String receiverAddr,
                               Long createdAtMs) {
    }

    /** 订单详情项（列表字段 + 物流轨迹）。 */
    public record AgentOrderDetailVO(String orderId, String userId, String username, String productId,
                                     String productName, String amount, String status, String receiverAddr,
                                     String logisticsTrace, Long createdAtMs) {
    }

    @Operation(summary = "订单分页", description = "多维过滤（userId/username/orderId/status），返回 {total, items}")
    @GetMapping
    public Mono<PageResult<AgentOrderVO>> page(@RequestParam(required = false) String userId,
                                               @RequestParam(required = false) String username,
                                               @RequestParam(required = false) String orderId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size, ServerWebExchange exchange) {
        OrderDirectoryQuery query = new OrderDirectoryQuery(userId, orderId, status, username, page, size);
        return blocking(exchange, () -> {
            requireEnabled();
            PageResult<OrderDirectoryRow> result = orderDirectoryService.page(query);
            List<AgentOrderVO> items = result.items().stream().map(this::toVO).collect(Collectors.toList());
            return new PageResult<>(result.total(), items);
        });
    }

    @Operation(summary = "订单详情", description = "含物流轨迹；不存在 404")
    @GetMapping("/{orderId}")
    public Mono<AgentOrderDetailVO> detail(@PathVariable String orderId, ServerWebExchange exchange) {
        return blocking(exchange, () -> {
            requireEnabled();
            OrderDirectoryRow row = orderDirectoryService.findDetail(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: " + orderId));
            return toDetailVO(row);
        });
    }

    @Operation(summary = "修改收货地址", description = "newAddress 非空；不存在 404")
    @PostMapping("/{orderId}/modify-address")
    public Mono<Void> modifyAddress(@PathVariable String orderId, @RequestBody ModifyAddressRequest request,
                                    ServerWebExchange exchange) {
        if (request == null || !StringUtils.hasText(request.newAddress())) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "newAddress required"));
        }
        return blocking(exchange, () -> {
            requireEnabled();
            translate(orderDirectoryService.modifyAddress(orderId, request.newAddress()), orderId);
            return null;
        });
    }

    @Operation(summary = "取消订单", description = "仅未发货可取消；不存在 404，状态不允许 409")
    @PostMapping("/{orderId}/cancel")
    public Mono<Void> cancel(@PathVariable String orderId, @RequestBody(required = false) CancelRequest request,
                             ServerWebExchange exchange) {
        String reason = request == null ? null : request.reason();
        return blocking(exchange, () -> {
            requireEnabled();
            translate(orderDirectoryService.cancel(orderId, reason), orderId);
            return null;
        });
    }

    // ---- 内部 ----

    private AgentOrderVO toVO(OrderDirectoryRow row) {
        return new AgentOrderVO(row.getOrderId(), row.getUserId(), row.getUsername(), row.getProductId(),
            row.getProductName(), formatAmount(row.getAmount()), row.getStatus(), row.getReceiverAddr(),
            row.getCreatedAtMs());
    }

    private AgentOrderDetailVO toDetailVO(OrderDirectoryRow row) {
        return new AgentOrderDetailVO(row.getOrderId(), row.getUserId(), row.getUsername(), row.getProductId(),
            row.getProductName(), formatAmount(row.getAmount()), row.getStatus(), row.getReceiverAddr(),
            row.getLogisticsTrace(), row.getCreatedAtMs());
    }

    /** 金额统一格式化为两位小数字符串（对外契约固定精度）。 */
    private static String formatAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 领域写结果 → HTTP 语义：NOT_FOUND→404、STATE_CONFLICT→409、OK→放行。 */
    private void translate(OrderMutationResult result, String orderId) {
        if (result == OrderMutationResult.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: " + orderId);
        }
        if (result == OrderMutationResult.STATE_CONFLICT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "order status does not allow this operation");
        }
    }

    /** 订单数据源未启用（mode!=jdbc）时语义化 503。 */
    private void requireEnabled() {
        if (!orderDirectoryService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单数据源未启用");
        }
    }

    private <T> Mono<T> blocking(ServerWebExchange exchange, Supplier<T> action) {
        AgentIdentity identity = exchange.getAttribute(AgentAuthWebFilter.AGENT_IDENTITY_ATTR);
        if (identity == null || !StringUtils.hasText(identity.tenantId())) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent token tenant is missing"));
        }
        // 即使 SQL 租户插件关闭，订单边界也必须使用服务端签名租户；不能继承调度线程残留值。
        return Mono.fromSupplier(() -> TenantContext.callWith(identity.tenantId(), action))
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(this::mapError);
    }

    /** 领域异常 → HTTP：ResponseStatusException 透传；其余（DB 异常等）503。 */
    private Throwable mapError(Throwable e) {
        if (e instanceof ResponseStatusException) {
            return e;
        }
        if (e instanceof IllegalStateException || e instanceof DataAccessException) {
            log.error("agent order request failed, errorCode={}", ERROR_REQUEST, e);
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单系统暂时不可用");
        }
        return e;
    }
}
