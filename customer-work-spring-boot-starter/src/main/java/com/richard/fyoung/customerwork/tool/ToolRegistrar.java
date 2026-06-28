package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MemberBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.ProductBackend;
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
    public static final String GROUP_PRESALE = "presale";
    public static final String GROUP_MEMBER = "member";
    public static final String GROUP_COMPLAINT = "complaint";
    public static final String GROUP_HUMAN = "human";

    private final OrderBackend orderBackend;
    private final AfterSalesBackend afterSalesBackend;
    private final KnowledgeBackend knowledgeBackend;
    private final ProductBackend productBackend;
    private final MemberBackend memberBackend;
    private final ComplaintBackend complaintBackend;
    private final PendingApprovalService approvalService;

    public ToolRegistrar(OrderBackend orderBackend,
                         AfterSalesBackend afterSalesBackend,
                         KnowledgeBackend knowledgeBackend,
                         ProductBackend productBackend,
                         MemberBackend memberBackend,
                         ComplaintBackend complaintBackend,
                         PendingApprovalService approvalService) {
        this.orderBackend = orderBackend;
        this.afterSalesBackend = afterSalesBackend;
        this.knowledgeBackend = knowledgeBackend;
        this.productBackend = productBackend;
        this.memberBackend = memberBackend;
        this.complaintBackend = complaintBackend;
        this.approvalService = approvalService;
    }

    /** 创建各业务域工具组并注册对应工具。 */
    public void registerBusinessTools(Toolkit toolkit) {
        toolkit.createToolGroup(GROUP_KNOWLEDGE, "知识库检索：产品政策、售后规则、发票运费等 FAQ", true);
        toolkit.createToolGroup(GROUP_ORDER, "订单与物流：查询/改址/取消/催发货", true);
        toolkit.createToolGroup(GROUP_AFTER_SALES, "售后：退款/退货/换货/价保/发票/进度（涉资金走人工确认）", true);
        toolkit.createToolGroup(GROUP_PRESALE, "售前导购：商品咨询/推荐/库存/优惠", true);
        toolkit.createToolGroup(GROUP_MEMBER, "会员/账户：积分/等级权益/账户问题", true);
        toolkit.createToolGroup(GROUP_COMPLAINT, "投诉工单：建单/查单", true);
        toolkit.createToolGroup(GROUP_HUMAN, "人工坐席转接与风险熔断", true);

        toolkit.registration().tool(new KnowledgeBaseTools(knowledgeBackend)).group(GROUP_KNOWLEDGE).apply();
        toolkit.registration().tool(new OrderTools(orderBackend)).group(GROUP_ORDER).apply();
        toolkit.registration().tool(new AfterSalesTools(afterSalesBackend, approvalService)).group(GROUP_AFTER_SALES).apply();
        toolkit.registration().tool(new ProductTools(productBackend)).group(GROUP_PRESALE).apply();
        toolkit.registration().tool(new MemberTools(memberBackend)).group(GROUP_MEMBER).apply();
        toolkit.registration().tool(new ComplaintTools(complaintBackend)).group(GROUP_COMPLAINT).apply();
        toolkit.registration().tool(new HumanHandoffTools()).group(GROUP_HUMAN).apply();
    }
}
