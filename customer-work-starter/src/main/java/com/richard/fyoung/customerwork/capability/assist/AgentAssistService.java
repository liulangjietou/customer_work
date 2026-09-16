package com.richard.fyoung.customerwork.capability.assist;

import org.springframework.stereotype.Service;

/**
 * 坐席辅助（Agent Assist，借鉴 AliGo 坐席辅助）：根据用户消息给人工坐席实时话术建议 + 知识提示 + 工具推荐。
 *
 * <p>规则匹配、离线确定性；可作为人工坐席工作台的旁挂提示，不直接发送给用户。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentAssistService {

    /** 按用户消息给出坐席建议（规则优先级：退款 &gt; 物流 &gt; 投诉 &gt; 发票 &gt; 默认）。 */
    public AssistSuggestion suggest(String userMessage) {
        String text = userMessage == null ? "" : userMessage;
        if (containsAny(text, "退款", "退货", "退钱")) {
            return new AssistSuggestion(
                "我会先核对您的订单和退款进度。请提供订单号及当前遇到的问题，具体处理条件和到账时间以核实结果为准。",
                "核对本租户已发布的退款政策及订单状态；未核实前不承诺退款资格或到账时间，资金操作遵守人工审批。",
                "checkRefundEligibility / submitRefund");
        }
        if (containsAny(text, "物流", "快递", "到哪了", "发货")) {
            return new AssistSuggestion(
                "我会为您核对物流状态。请提供订单号或运单号，查到明确记录后再向您说明。",
                "以实际物流记录为依据；尚未查询时不声明已发货、已签收或预计到达时间。",
                "queryLogistics / urgeShipment");
        }
        if (containsAny(text, "投诉", "差评", "举报", "态度")) {
            return new AssistSuggestion(
                "很抱歉这次体验让您不满意。请告诉我具体经过和希望得到的处理，我会为您核对并记录。",
                "注意情绪安抚，避免争辩和推诿；处理时效及升级路径以本租户已发布规范为准。",
                "fileComplaint / transferToHuman");
        }
        if (containsAny(text, "发票", "开票")) {
            return new AssistSuggestion(
                "我会先核对订单的开票情况。请确认需要的发票类型，具体开票条件和发送方式以核实结果为准。",
                "核对本租户已发布的开票规则与订单记录；未完成受理前不声明已开票或承诺发送时间。",
                "requestInvoice");
        }
        return new AssistSuggestion(
            "请您补充一下具体遇到的问题和希望得到的帮助，我会根据可核实的信息为您处理。",
            "先澄清诉求，再核对本租户已发布知识；缺少依据时保留待核实事项。",
            "searchKnowledge");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
