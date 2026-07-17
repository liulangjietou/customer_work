package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 投诉工单工具组：建单 / 查单。业务委托给可替换的 {@link ComplaintBackend}。
 *
 * <p>把投诉从"仅识别意图"升级为"可建单 + 可跟踪"，与 {@code transferToHuman} 协同（高危/升级转人工）。</p>
 * @author owlzhangfq@gmail.com
 */
public class ComplaintTools {

    private final ComplaintBackend backend;

    public ComplaintTools(ComplaintBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "为用户创建投诉工单并返回工单号。用户明确投诉（服务/质量/物流等）需登记跟进时调用。")
    public Mono<String> fileComplaint(
            @ToolParam(name = "orderId", description = "相关订单号（无则填 '无'）")
            String orderId,
            @ToolParam(name = "content", description = "投诉内容")
            String content) {
        return backend.fileComplaint(orderId, content);
    }

    @Tool(description = "查询投诉工单的处理状态。用户问'我的投诉处理得怎么样了'时调用。")
    public Mono<String> queryComplaint(
            @ToolParam(name = "ticketId", description = "投诉工单号，如 CP123456")
            String ticketId) {
        return backend.queryComplaint(ticketId);
    }
}
