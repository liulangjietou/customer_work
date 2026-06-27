package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

/**
 * 业务工具注册器：把按业务域分组的工具注册进 {@link Toolkit}。
 *
 * <p>工具壳（{@link OrderTools} 等）由可替换的后端（{@link OrderBackend} 等）驱动——使用者只需
 * 提供自定义后端 Bean 即可接入自有系统，本注册器与工具壳无需改动。新增业务域时在此追加一个组即可。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ToolRegistrar {

    public static final String GROUP_KNOWLEDGE = "knowledge";
    public static final String GROUP_ORDER = "order";
    public static final String GROUP_AFTER_SALES = "after_sales";
    public static final String GROUP_HUMAN = "human";

    private final OrderBackend orderBackend;
    private final AfterSalesBackend afterSalesBackend;
    private final KnowledgeBackend knowledgeBackend;
    private final PendingApprovalService approvalService;

    public ToolRegistrar(OrderBackend orderBackend,
                         AfterSalesBackend afterSalesBackend,
                         KnowledgeBackend knowledgeBackend,
                         PendingApprovalService approvalService) {
        this.orderBackend = orderBackend;
        this.afterSalesBackend = afterSalesBackend;
        this.knowledgeBackend = knowledgeBackend;
        this.approvalService = approvalService;
    }

    /** 创建四个业务域工具组并注册对应工具。 */
    public void registerBusinessTools(Toolkit toolkit) {
        toolkit.createToolGroup(GROUP_KNOWLEDGE, "知识库检索：产品政策、售后规则、发票运费等 FAQ", true);
        toolkit.createToolGroup(GROUP_ORDER, "订单与物流查询", true);
        toolkit.createToolGroup(GROUP_AFTER_SALES, "售后与退款（涉资金走人工确认）", true);
        toolkit.createToolGroup(GROUP_HUMAN, "人工坐席转接与风险熔断", true);

        toolkit.registration().tool(new KnowledgeBaseTools(knowledgeBackend)).group(GROUP_KNOWLEDGE).apply();
        toolkit.registration().tool(new OrderTools(orderBackend)).group(GROUP_ORDER).apply();
        toolkit.registration().tool(new AfterSalesTools(afterSalesBackend, approvalService)).group(GROUP_AFTER_SALES).apply();
        toolkit.registration().tool(new HumanHandoffTools()).group(GROUP_HUMAN).apply();
    }
}
