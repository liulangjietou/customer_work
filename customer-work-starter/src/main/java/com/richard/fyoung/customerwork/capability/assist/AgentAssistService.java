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
                "先安抚并确认订单号与原因，告知七天无理由规则；符合条件可发起退款（涉资金走人工放行）。",
                "退款政策：七天无理由、原路退回 1-3 个工作日。",
                "checkRefundEligibility / submitRefund");
        }
        if (containsAny(text, "物流", "快递", "到哪了", "发货")) {
            return new AssistSuggestion(
                "查询物流轨迹后告知最新状态与预计时效；如长时间未更新，主动安抚并提报异常。",
                "可催发货或登记物流异常。",
                "queryLogistics / urgeShipment");
        }
        if (containsAny(text, "投诉", "差评", "举报", "态度")) {
            return new AssistSuggestion(
                "先共情致歉，避免争辩；登记投诉工单并给出处理时效，必要时升级转人工。",
                "高敏感：注意情绪安抚，禁用争辩/推诿话术。",
                "fileComplaint / transferToHuman");
        }
        if (containsAny(text, "发票", "开票")) {
            return new AssistSuggestion(
                "确认发票抬头与类型后受理开票，告知到账方式与时效。",
                "电子发票 24 小时内发送至预留邮箱。",
                "requestInvoice");
        }
        return new AssistSuggestion(
            "先澄清用户具体诉求，再检索知识库给出准确答复；拿不准的转人工。",
            "优先检索知识库获取权威口径。",
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
