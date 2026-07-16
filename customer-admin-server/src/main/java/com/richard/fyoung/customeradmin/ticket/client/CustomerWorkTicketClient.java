package com.richard.fyoung.customeradmin.ticket.client;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ticket.dto.OrderDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.OrderPageResult;
import com.richard.fyoung.customeradmin.ticket.dto.TicketDetailVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketMessageVO;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageQuery;
import com.richard.fyoung.customeradmin.ticket.dto.TicketPageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 封装对 8080 坐席 API 的全部调用。
 *
 * <p>8080 用语义化 HTTP 状态码、无 {@code Result} 包装：状态冲突（被抢单/非法流转）409、未认证 401。
 * 本客户端把上游异常统一翻译为 {@link BizException}（409→状态冲突、401→鉴权失败、
 * 连接失败/超时→服务不可用），由 {@code GlobalExceptionHandler} 转成标准响应体，
 * 不让裸的 {@code RestClientException} 穿透到 Controller。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CustomerWorkTicketClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerWorkTicketClient.class);

    private static final String TICKETS_PATH = "/api/customer/agent/tickets";
    private static final String ORDERS_PATH = "/api/customer/agent/orders";
    private static final int HTTP_CONFLICT = 409;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_NOT_FOUND = 404;

    private static final String CLIENT_IO_FAIL = "TICKET-CLIENT-IO-FAIL";
    private static final String CLIENT_AUTH_FAIL = "TICKET-CLIENT-AUTH-FAIL";
    private static final String CLIENT_REQUEST_FAIL = "TICKET-CLIENT-REQUEST-FAIL";

    private static final ParameterizedTypeReference<List<TicketMessageVO>> MESSAGE_LIST_TYPE =
        new ParameterizedTypeReference<>() {
        };

    private final RestClient restClient;

    public CustomerWorkTicketClient(RestClient customerWorkRestClient) {
        this.restClient = customerWorkRestClient;
    }

    /** 分页查询工单：admin 的 pageNum/pageSize 映射为 8080 的 page/size。 */
    public TicketPageResult page(TicketPageQuery query) {
        return execute(() -> restClient.get()
            .uri(builder -> builder.path(TICKETS_PATH)
                .queryParamIfPresent("status", Optional.ofNullable(query.getStatus()))
                .queryParamIfPresent("assignee", Optional.ofNullable(query.getAssignee()))
                .queryParamIfPresent("category", Optional.ofNullable(query.getCategory()))
                .queryParamIfPresent("priority", Optional.ofNullable(query.getPriority()))
                .queryParam("page", query.getPageNum())
                .queryParam("size", query.getPageSize())
                .build())
            .retrieve()
            .body(TicketPageResult.class));
    }

    /** 工单详情（含事件轨迹）。 */
    public TicketDetailVO detail(String id) {
        return execute(() -> restClient.get()
            .uri(TICKETS_PATH + "/{id}", id)
            .retrieve()
            .body(TicketDetailVO.class));
    }

    /** 工单会话消息（游标翻页：beforeId 之前的 limit 条）。 */
    public List<TicketMessageVO> messages(String id, Long beforeId, Integer limit) {
        return execute(() -> restClient.get()
            .uri(builder -> builder.path(TICKETS_PATH + "/{id}/messages")
                .queryParamIfPresent("beforeId", Optional.ofNullable(beforeId))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .build(id))
            .retrieve()
            .body(MESSAGE_LIST_TYPE));
    }

    /** 抢单（受理），坐席身份由 X-Agent-Token 携带。 */
    public void claim(String id) {
        post(id, "/claim", null);
    }

    /** 坐席回复。 */
    public void reply(String id, String content) {
        post(id, "/reply", Map.of("content", content));
    }

    /** 挂起（reason 可选）。 */
    public void hold(String id, String reason) {
        post(id, "/hold", StringUtils.hasText(reason) ? Map.of("reason", reason) : null);
    }

    /** 恢复（取消挂起），8080 无参数。 */
    public void resume(String id) {
        post(id, "/resume", null);
    }

    /** 转派（toAgent 可选）。 */
    public void transfer(String id, String toAgent) {
        post(id, "/transfer", StringUtils.hasText(toAgent) ? Map.of("toAgent", toAgent) : null);
    }

    /** 解决。 */
    public void resolve(String id, String note) {
        post(id, "/resolve", Map.of("note", note));
    }

    /** 关闭。 */
    public void close(String id, String reason) {
        post(id, "/close", Map.of("reason", reason));
    }

    /** 调整优先级。 */
    public void updatePriority(String id, String priority) {
        put(id, "/priority", Map.of("priority", priority));
    }

    /** 调整分类。 */
    public void updateCategory(String id, String category) {
        put(id, "/category", Map.of("category", category));
    }

    // ==================== 用户订单（坐席侧）：/api/customer/agent/orders ====================

    /** 订单分页查询：admin 的 pageNum/pageSize 映射为 8080 的 page/size。 */
    public OrderPageResult pageOrders(OrderPageQuery query) {
        return executeOrder(() -> restClient.get()
            .uri(builder -> builder.path(ORDERS_PATH)
                .queryParamIfPresent("userId", Optional.ofNullable(query.getUserId()))
                .queryParamIfPresent("username", Optional.ofNullable(query.getUsername()))
                .queryParamIfPresent("orderId", Optional.ofNullable(query.getOrderId()))
                .queryParamIfPresent("status", Optional.ofNullable(query.getStatus()))
                .queryParam("page", query.getPageNum())
                .queryParam("size", query.getPageSize())
                .build())
            .retrieve()
            .body(OrderPageResult.class));
    }

    /** 订单详情（含物流轨迹）；不存在 404→ORDER_NOT_FOUND。 */
    public OrderDetailVO orderDetail(String orderId) {
        return executeOrder(() -> restClient.get()
            .uri(ORDERS_PATH + "/{orderId}", orderId)
            .retrieve()
            .body(OrderDetailVO.class));
    }

    /** 修改收货地址；不存在 404→ORDER_NOT_FOUND。 */
    public void modifyOrderAddress(String orderId, String newAddress) {
        executeOrder(() -> restClient.post()
            .uri(ORDERS_PATH + "/{orderId}/modify-address", orderId)
            .body(Map.of("newAddress", newAddress))
            .retrieve()
            .toBodilessEntity());
    }

    /** 取消订单；不存在 404→ORDER_NOT_FOUND，状态不允许 409→ORDER_STATE_CONFLICT。 */
    public void cancelOrder(String orderId, String reason) {
        executeOrder(() -> {
            RestClient.RequestBodySpec spec = restClient.post().uri(ORDERS_PATH + "/{orderId}/cancel", orderId);
            if (StringUtils.hasText(reason)) {
                spec.body(Map.of("reason", reason));
            }
            return spec.retrieve().toBodilessEntity();
        });
    }

    private void post(String id, String action, Object body) {
        execute(() -> {
            RestClient.RequestBodySpec spec = restClient.post().uri(TICKETS_PATH + "/{id}" + action, id);
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().toBodilessEntity();
        });
    }

    private void put(String id, String action, Object body) {
        execute(() -> restClient.put()
            .uri(TICKETS_PATH + "/{id}" + action, id)
            .body(body)
            .retrieve()
            .toBodilessEntity());
    }

    /**
     * 统一执行 + 异常翻译入口：409→状态冲突、401→鉴权失败、连接失败/超时→服务不可用。
     * 一处防御，全部调用点复用，避免每个方法各写一遍 try-catch。
     */
    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            // 连接被拒 / 读取超时等 IO 层失败（RestClient 把 IOException 包成 ResourceAccessException）
            log.error("customer-work request io failed, code={}", CLIENT_IO_FAIL, e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE);
        }
    }

    private BizException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HTTP_CONFLICT) {
            return new BizException(ResultCode.TICKET_STATE_CONFLICT);
        }
        if (status == HTTP_UNAUTHORIZED) {
            log.error("customer-work auth failed, code={}, status={}", CLIENT_AUTH_FAIL, status, e);
            return new BizException(ResultCode.CUSTOMER_WORK_AUTH_FAILED);
        }
        log.error("customer-work request failed, code={}, status={}", CLIENT_REQUEST_FAIL, status, e);
        return new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE);
    }

    /**
     * 订单调用的执行 + 异常翻译：语义与 {@link #execute} 一致，但对 404/409 走订单专属错误码
     * （404→订单不存在、409→订单状态不允许），其余复用鉴权失败 / 服务不可用。
     */
    private <T> T executeOrder(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw translateOrder(e);
        } catch (ResourceAccessException e) {
            log.error("customer-work order request io failed, code={}", CLIENT_IO_FAIL, e);
            throw new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE);
        }
    }

    private BizException translateOrder(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HTTP_NOT_FOUND) {
            return new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (status == HTTP_CONFLICT) {
            return new BizException(ResultCode.ORDER_STATE_CONFLICT);
        }
        if (status == HTTP_UNAUTHORIZED) {
            log.error("customer-work auth failed, code={}, status={}", CLIENT_AUTH_FAIL, status, e);
            return new BizException(ResultCode.CUSTOMER_WORK_AUTH_FAILED);
        }
        log.error("customer-work order request failed, code={}, status={}", CLIENT_REQUEST_FAIL, status, e);
        return new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE);
    }
}
