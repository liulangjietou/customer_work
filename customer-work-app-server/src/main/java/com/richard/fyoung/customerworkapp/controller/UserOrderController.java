package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerworkapp.dao.UserOrderDao;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao.OrderView;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao.OwnedOrder;
import com.richard.fyoung.customerwork.safety.security.UserAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 用户侧订单端点（{@code /api/customer/user/orders}，JWT 鉴权）。
 *
 * <p>当前用户主体由 {@code UserAuthWebFilter} 放入 exchange 属性，本控制器不信任任何客户端自报的 userId：
 * 列表仅返回当前用户订单，详情按订单归属校验（非本人和不存在统一 404，避免枚举）。订单数据源未启用（mode!=jdbc）时
 * 返回 503 语义化提示。</p>
 * @author owlzhangfq@gmail.com
 */
@RestController
@RequestMapping("/api/customer/user/orders")
@Tag(name = "用户订单", description = "订单列表 / 订单详情（含物流轨迹）")
public class UserOrderController {

    private final UserOrderDao orderDao;

    public UserOrderController(UserOrderDao orderDao) {
        this.orderDao = orderDao;
    }

    @Operation(summary = "我的订单列表", description = "当前用户全部订单，按下单时间倒序；不含物流轨迹")
    @GetMapping
    public Mono<List<OrderView>> list(ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            requireEnabled();
            return orderDao.listByUser(user.userId());
        });
    }

    @Operation(summary = "订单详情", description = "含物流轨迹；非本人和不存在统一返回 404")
    @GetMapping("/{orderId}")
    public Mono<OrderView> detail(@PathVariable String orderId, ServerWebExchange exchange) {
        UserPrincipal user = principal(exchange);
        return blocking(() -> {
            requireEnabled();
            OwnedOrder owned = orderDao.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: " + orderId));
            if (!user.userId().equals(owned.userId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: " + orderId);
            }
            return owned.view();
        });
    }

    // ---- 内部 ----

    /** 从 exchange 属性取当前用户主体（过滤器已保证存在，此处为单一防御点）。 */
    private UserPrincipal principal(ServerWebExchange exchange) {
        UserPrincipal user = exchange.getAttribute(UserAuthWebFilter.PRINCIPAL_ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return user;
    }

    /** 订单数据源未启用（mode!=jdbc）时语义化 503。 */
    private void requireEnabled() {
        if (!orderDao.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单数据源未启用");
        }
    }

    /** 阻塞 JDBC 在 boundedElastic 执行并统一翻译领域异常。 */
    private <T> Mono<T> blocking(Callable<T> callable) {
        return Mono.fromCallable(callable)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(this::translate);
    }

    /** 领域异常 → HTTP 状态：ResponseStatusException 原样透传；查询异常 503。 */
    private Throwable translate(Throwable e) {
        if (e instanceof ResponseStatusException) {
            return e;
        }
        if (e instanceof IllegalStateException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "订单系统暂时不可用");
        }
        return e;
    }
}
