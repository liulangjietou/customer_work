package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapService;
import org.springframework.beans.factory.annotation.Autowired;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MemberBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.ProductBackend;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;
import java.util.HashSet;
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

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrar.class);

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
    private final HandoffService handoffService;
    /** 可空：保留构造合同；生产 HandoffService 内部已统一依赖同一个 TicketService。 */
    private final TicketService ticketService;

    /**
     * 知识盲区分析；可空，未装配时知识工具行为与从前完全一致。
     *
     * <p>用 setter 而非构造参数：本类构造器已有 9 个参数，再塞一个可选依赖只会让下游手工装配更难写；
     * 而它是纯旁路统计，缺席不影响任何工具的行为。</p>
     */
    private KnowledgeGapService knowledgeGapService;

    public ToolRegistrar(OrderBackend orderBackend,
                         AfterSalesBackend afterSalesBackend,
                         KnowledgeBackend knowledgeBackend,
                         ProductBackend productBackend,
                         MemberBackend memberBackend,
                         ComplaintBackend complaintBackend,
                         PendingApprovalService approvalService,
                         HandoffService handoffService,
                         TicketService ticketService) {
        this.orderBackend = orderBackend;
        this.afterSalesBackend = afterSalesBackend;
        this.knowledgeBackend = knowledgeBackend;
        this.productBackend = productBackend;
        this.memberBackend = memberBackend;
        this.complaintBackend = complaintBackend;
        this.approvalService = approvalService;
        this.handoffService = handoffService;
        this.ticketService = ticketService;
    }

    /**
     * 槽位填充（可选）。
     *
     * <p>装配了才把退款信息收集工具交给模型——它是<b>增益而非必需</b>：
     * 没有它时模型照样会自己追问缺失信息，只是丢了跨轮次持久化与格式校验。
     * 走可选注入而不是加构造参数，是因为 {@code new ToolRegistrar(...)} 在四处被调用
     * （含 channel 与三个测试），加参数会连锁改动，而这个依赖本就不是必需的。</p>
     */
    private SlotFillingService slotFillingService;

    /** 注入槽位填充（可选，Spring 装配时自动调用；未装配则不注册退款信息收集工具）。 */
    @Autowired(required = false)
    public void setSlotFillingService(SlotFillingService slotFillingService) {
        this.slotFillingService = slotFillingService;
    }

    /** 注入知识盲区分析（可选，Spring 装配时自动调用；未装配则保持 null）。 */
    @Autowired(required = false)
    public void setKnowledgeGapService(KnowledgeGapService knowledgeGapService) {
        this.knowledgeGapService = knowledgeGapService;
    }

    /** 创建各业务域工具组并注册对应工具（无会话上下文：转人工工具不驱动工单域）。 */
    public void registerBusinessTools(Toolkit toolkit) {
        registerBusinessTools(toolkit, null);
    }

    /**
     * 创建各业务域工具组并注册对应工具。
     *
     * @param sessionId 会话标识；非空且工单域已装配时，转人工工具会以真实会话驱动 {@link TicketService}
     */
    public void registerBusinessTools(Toolkit toolkit, String sessionId) {
        registerBusinessTools(toolkit, sessionId, Set.of());
    }

    /**
     * 创建各业务域工具组并注册对应工具，跳过本部署停用的组。
     *
     * <p><b>为什么允许停用</b>：全部业务工具的 schema 实测约 4100 token，
     * 占 {@code context.max-token} 默认值的 52%——每一轮对话有一半以上的上下文预算
     * 花在工具定义上。一个只做售后的部署，售前导购那几个工具从头到尾用不上，却每轮都在付这份成本。</p>
     *
     * <p><b>转人工组不可停用</b>：把它关掉，用户就被困在智能体里出不来了。
     * 这不是配置项该有的权力，因此在这里硬性忽略，而不是靠文档提醒。</p>
     *
     * @param disabledGroups 本部署不注册的组；转人工组即使出现在其中也会被忽略
     */
    public void registerBusinessTools(Toolkit toolkit, String sessionId, Set<String> disabledGroups) {
        Set<String> disabled = normalizeDisabled(disabledGroups);

        createGroupIfEnabled(toolkit, disabled, GROUP_KNOWLEDGE,
            "知识库检索：产品政策、售后规则、发票运费等 FAQ");
        createGroupIfEnabled(toolkit, disabled, GROUP_ORDER, "订单与物流：查询/改址/取消/催发货");
        createGroupIfEnabled(toolkit, disabled, GROUP_AFTER_SALES,
            "售后：退款/退货/换货/价保/发票/进度（涉资金走人工确认）");
        createGroupIfEnabled(toolkit, disabled, GROUP_PRESALE, "售前导购：商品咨询/推荐/库存/优惠");
        createGroupIfEnabled(toolkit, disabled, GROUP_MEMBER, "会员/账户：积分/等级权益/账户问题");
        createGroupIfEnabled(toolkit, disabled, GROUP_COMPLAINT, "投诉工单：建单/查单");
        toolkit.createToolGroup(GROUP_HUMAN, "人工坐席转接与风险熔断", true);

        if (!disabled.contains(GROUP_KNOWLEDGE)) {
            toolkit.registration().tool(new KnowledgeBaseTools(knowledgeBackend, knowledgeGapService))
                .group(GROUP_KNOWLEDGE).apply();
        }
        if (!disabled.contains(GROUP_ORDER)) {
            toolkit.registration().tool(new OrderTools(orderBackend)).group(GROUP_ORDER).apply();
        }
        if (!disabled.contains(GROUP_AFTER_SALES)) {
            toolkit.registration().tool(new AfterSalesTools(afterSalesBackend, approvalService, sessionId))
                .group(GROUP_AFTER_SALES).apply();
            // 退款信息收集并入售后组而不是新开一组：工具面的 schema 已占上下文预算约一半
            //（见 ToolSurfaceCostTest），新开组等于让所有部署都多付一份组描述的钱
            if (slotFillingService != null) {
                toolkit.registration().tool(new RefundIntakeTools(slotFillingService, sessionId))
                    .group(GROUP_AFTER_SALES).apply();
            }
        }
        if (!disabled.contains(GROUP_PRESALE)) {
            toolkit.registration().tool(new ProductTools(productBackend)).group(GROUP_PRESALE).apply();
        }
        if (!disabled.contains(GROUP_MEMBER)) {
            toolkit.registration().tool(new MemberTools(memberBackend)).group(GROUP_MEMBER).apply();
        }
        if (!disabled.contains(GROUP_COMPLAINT)) {
            toolkit.registration().tool(new ComplaintTools(complaintBackend)).group(GROUP_COMPLAINT).apply();
        }
        toolkit.registration().tool(buildHumanHandoffTools(sessionId)).group(GROUP_HUMAN).apply();

        if (!disabled.isEmpty()) {
            log.info("business tool groups disabled by configuration: {}", disabled);
        }
    }

    /** 归一化停用清单：去空白、转小写，并强制保留转人工组。 */
    private Set<String> normalizeDisabled(Set<String> disabledGroups) {
        if (disabledGroups == null || disabledGroups.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String group : disabledGroups) {
            if (group == null || group.isBlank()) {
                continue;
            }
            String code = group.trim().toLowerCase();
            if (GROUP_HUMAN.equals(code)) {
                // 不是"忽略了一个笔误"，是明确拒绝一个会把用户困住的配置
                log.error("refuse to disable human handoff tool group, code={}", "TOOL-GROUP-HUMAN-PROTECTED");
                continue;
            }
            normalized.add(code);
        }
        return Set.copyOf(normalized);
    }

    private void createGroupIfEnabled(Toolkit toolkit, Set<String> disabled, String group, String description) {
        if (!disabled.contains(group)) {
            toolkit.createToolGroup(group, description, true);
        }
    }

    /** 有真实会话时传入 sessionId；HandoffService 内部只推进一次权威工单状态机。 */
    private HumanHandoffTools buildHumanHandoffTools(String sessionId) {
        if (sessionId != null && ticketService != null) {
            return new HumanHandoffTools(handoffService, ticketService, sessionId);
        }
        return new HumanHandoffTools(handoffService);
    }
}
