package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多 Agent 编排配置。 */
@Data
public class MultiAgentProperties {
    /** 是否启用多 Agent 编排端点。 */
    private boolean enabled = true;
    /** 编排模式：fanout（并行多专家聚合）| sequential（流水串行细化）。 */
    private String mode = "fanout";
    /** 每个专家 Agent 的 ReAct 最大轮次。 */
    private int maxIters = 6;
    /** 并行 fanout 的最大并发度（同时在跑的专家数上限，&lt;=0 视为 1）。 */
    private int maxConcurrency = 8;
    /** 单个专家调用超时（秒）：超时按错误隔离，不拖垮整体并行。 */
    private long timeoutSeconds = 60;
    /** 智能路由：先用分诊器判断意图，只把问题发给相关专家（省 token / 更准）；关则广播全部专家。 */
    private boolean routingEnabled = true;
    /** 规则快车道：在 LLM 分诊前先用关键词规则命中确定意图（命中即直路由，省一次模型调用、提准降延迟）。 */
    private boolean fastRouteEnabled = true;
    /** reduce 归纳：fanout 后用归纳器把多专家结论二次合成统一口径回复；关则直接拼接各专家结论。 */
    private boolean reduceEnabled = true;

    /**
     * 专家编排拓扑（能力差距 P1-15）。
     *
     * <p><b>此前这三样东西全部硬编码在 {@code MultiAgentOrchestrator} 里</b>：专家清单与提示词写死在
     * {@code buildSpecialists()}、意图到专家的映射写死成一个 switch、快车道关键词写死成一张静态表。
     * 也就是说改一句专家提示词、调整一个业务触发词、增删一个专家，都要改代码重新发版。</p>
     *
     * <p>默认值<b>逐字等于</b>改造前那三个专家，所以不配置时行为完全不变；
     * {@code MultiAgentTopologyDefaultsTest} 对此下断言。</p>
     */
    private List<Expert> experts = defaultExperts();

    /**
     * 快车道关键词表：意图 → 触发词。命中<b>唯一</b>意图时直路由、跳过 LLM 分诊；
     * 命中多类（语义可能跨域）或无命中则交慢车道。
     *
     * <p><b>为什么挂在意图上而不是专家上</b>：快车道的判定是「命中唯一<b>意图</b>」，
     * 而一个专家可以认领多个意图（售后专家同时管 refund 与 complaint）。
     * 按专家分组的话，「我要退款而且要投诉」会命中同一个专家、被当成唯一命中直路由，
     * 而原实现会因为命中两类意图交给 LLM 分诊——那是一次不该夹带的行为变更。
     * 改造过程中差点这么写，写默认值时才发现 complaint 那六个触发词无处安放。</p>
     */
    private Map<String, List<String>> routeKeywords = defaultRouteKeywords();

    /**
     * 一个专家的完整定义。
     *
     * <p><b>工具组的可选值受限于编排器手里有什么</b>：{@code MultiAgentOrchestrator} 只注入了
     * 订单、售后、知识库三个后端，因此 {@code toolGroups} 目前只认 {@code order} /
     * {@code after_sales} / {@code knowledge}。填了别的组会在构建时记 error 并跳过——
     * <b>刻意不静默忽略</b>：「配了不生效」比没有这个配置项更糟。
     * 要支持售前、会员、投诉那几个组，得让编排器改用 {@code ToolRegistrar}，那是独立的一件事。</p>
     */
    @Data
    public static class Expert {
        /** 专家名，同时是 Agent 名与路由匹配键；不可重复。 */
        private String name;
        /** 系统提示词。 */
        private String sysPrompt;
        /** 挂载的工具组，见类注释里的可选值说明。 */
        private List<String> toolGroups = new ArrayList<>();
        /** 认领的意图；LLM 分诊或快车道判出这些意图之一时，问题发给本专家。 */
        private List<String> intents = new ArrayList<>();
        /** 关闭后不参与任何路由与广播。 */
        private boolean enabled = true;
        /** sequential 模式下的流转顺序，小的先跑。 */
        private int order = 100;
    }

    /**
     * 改造前硬编码的那三个专家，逐字保留。
     *
     * <p>提示词一个字都不能改：它们是当前线上行为的一部分，
     * 「顺手润色一下」等于在一次配置化重构里夹带一次行为变更。</p>
     */
    private static List<Expert> defaultExperts() {
        List<Expert> experts = new ArrayList<>();
        experts.add(expert("OrderExpert",
            "你是订单与物流专家。只就订单状态、物流轨迹、金额等问题作答，调用订单工具查询后回答；与你无关的问题简要说明并建议转交对应专家。",
            List.of("order"), List.of("order"), 10));
        experts.add(expert("AfterSalesExpert",
            "你是售后与退款专家。处理退款资格校验与退款工单；涉及资金只生成待人工确认工单，绝不承诺已打款。",
            List.of("after_sales"), List.of("refund", "complaint"), 20));
        experts.add(expert("KnowledgeExpert",
            "你是政策咨询专家。依据知识库回答退换货、发票、运费等政策问题，并保留来源标注。",
            List.of("knowledge"), List.of("consult"), 30));
        return experts;
    }

    /** 改造前的静态关键词表，逐字保留；{@link LinkedHashMap} 固定遍历序。 */
    private static Map<String, List<String>> defaultRouteKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("refund", List.of("退款", "退货", "退钱", "申请退", "已读不退"));
        keywords.put("order", List.of("物流", "快递", "到哪了", "发货", "签收", "运单", "几天到"));
        keywords.put("complaint", List.of("投诉", "差评", "举报", "态度", "315", "曝光"));
        keywords.put("consult", List.of("发票", "运费", "政策", "几天无理由", "保修", "能不能开票"));
        return keywords;
    }

    private static Expert expert(String name, String sysPrompt, List<String> toolGroups,
                                 List<String> intents, int order) {
        Expert expert = new Expert();
        expert.setName(name);
        expert.setSysPrompt(sysPrompt);
        expert.setToolGroups(new ArrayList<>(toolGroups));
        expert.setIntents(new ArrayList<>(intents));
        expert.setOrder(order);
        return expert;
    }
}
