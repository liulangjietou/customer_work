package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投诉工单后端的默认演示实现（内存工单库）。生产替换为真实工单/客诉系统。
 * @author owlzhangfq@gmail.com
 */
public class MockComplaintBackend implements ComplaintBackend {

    private static final Logger log = LoggerFactory.getLogger(MockComplaintBackend.class);

    private static final String ID_PREFIX = "CP";

    /** ticketId -> 工单内容（演示用内存存储）。 */
    private final ConcurrentHashMap<String, String> tickets = new ConcurrentHashMap<>();

    @Override
    public Mono<String> fileComplaint(String orderId, String content) {
        return Mono.fromSupplier(() -> {
            String ticketId = ID_PREFIX + System.currentTimeMillis();
            tickets.put(ticketId, "订单=" + orderId + "，内容=" + content);
            log.info("[MockComplaintBackend] file complaint: ticket={}, order={}", ticketId, orderId);
            return "已为您创建投诉工单 " + ticketId + "（订单 " + orderId + "），"
                + "我们将在 24 小时内跟进处理，结果将短信通知您。";
        }).delayElement(Duration.ofMillis(80));
    }

    @Override
    public Mono<String> queryComplaint(String ticketId) {
        return Mono.fromSupplier(() -> {
            log.info("[MockComplaintBackend] query complaint: {}", ticketId);
            if (!tickets.containsKey(ticketId)) {
                return "未查询到投诉工单 " + ticketId + "，请核对工单号。";
            }
            return "投诉工单 " + ticketId + " 当前状态：处理中，已分派至对应部门，预计 1 个工作日内回复。";
        }).delayElement(Duration.ofMillis(70));
    }
}
