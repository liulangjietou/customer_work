package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TypeSafe Jev（System One 结构化决策模型）接入配置。
 *
 * <p>Jev 不生成文本，只返回带概率与置信度的结构化决策（Choice / Score / Noul），
 * 官方文档：https://docs.typesafe.ai/introduction 。</p>
 *
 * <h3>贯穿所有决策点的一条原则</h3>
 * <p><b>Jev 在安全相关决策上只能让系统更保守，永远不能比未接入时更宽松。</b>
 * 答复闸门只能额外拦截、缓存判定只能额外拒绝写入、退款只能额外转人工。
 * 唯一放宽方向的是工具收窄，它判错的代价是「办不了事」而非安全事故，用高阈值兜底且转人工组永不收窄。
 * 由此 Jev 不可用时（未开启、超时、熔断、响应非法），每个决策点都自动退回未接入时的行为。</p>
 *
 * <p>阈值默认值的依据写在各字段上。<b>默认值刻意保持字面量</b>：配置元数据处理器只从字面量提取默认值。</p>
 */
@Data
public class TypeSafeProperties {
    /** 总开关，默认关。开启时必须配置 {@link #apiKey}，缺失则启动失败（fast fail，不静默降级）。 */
    private boolean enabled = false;
    /** API Key，在 https://console.typesafe.ai/keys 获取；经环境变量 CUSTOMER_WORK_TYPESAFE_API_KEY 注入（Spring 宽松绑定），勿入库。 */
    private String apiKey;
    /** API 根地址。 */
    private String baseUrl = "https://api.typesafe.ai";
    /** 模型名，jev-latest 跟随官方最新版本。 */
    private String model = "jev-latest";
    /** 建连超时（毫秒）。 */
    private long connectTimeoutMs = 2000;
    /** 安全类决策（转人工、答复闸门、退款风险）的调用超时：宁可多等一会儿也要本轮生效。 */
    private long timeoutMs = 3000;
    /** 优化类决策（仅工具收窄时）的调用超时：超时即按「无决策」走原路径，不拖慢对话。 */
    private long fastTimeoutMs = 1200;

    /**
     * 意图选项（Choice 的 criteria）：意图编码 → 描述。
     * {@code other} 表示无法归类，任何决策点都不会据它采取动作。
     * 编码与 {@code /consult} 专家的 intents、工具收窄的 {@link ToolScope#intentGroups} 对应。
     */
    private Map<String, String> intentCriteria = defaultIntentCriteria();

    /**
     * {@code /consult} 多 Agent 意图路由的置信度门槛。判错的代价是问错专家（专家会说明与己无关），
     * 比工具收窄轻，故低于工具收窄的门槛。
     */
    private double intentMinConfidence = 0.6;

    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Escalation escalation = new Escalation();
    private final ToolScope toolScope = new ToolScope();
    private final PaymentClaim paymentClaim = new PaymentClaim();
    private final CacheGuard cacheGuard = new CacheGuard();
    private final RefundRisk refundRisk = new RefundRisk();

    /** 熔断：连续失败达到门槛后，一段时间内直接跳过 Jev，避免每轮对话都干等超时。 */
    @Data
    public static class CircuitBreaker {
        /** 连续失败多少次打开熔断。 */
        private int failureThreshold = 5;
        /** 打开后多久放一次试探请求（毫秒）。 */
        private long openDurationMs = 30000;
    }

    /**
     * 情绪/升级判定 → 两段式转人工。
     *
     * <p>Score 的 score 是期望值（会落在两档之间），所以「是否处于最高档」按概率最大的档位判定，
     * 而不是比较 score 是否等于最高档编号。</p>
     */
    @Data
    public static class Escalation {
        private boolean enabled = true;
        /** 最可能档位是最高档且置信度不低于此值时，直接建工单转人工。 */
        private double autoHandoffMinConfidence = 0.85;
        /** 档位期望值不低于 {@link #hintMinScore} 且置信度不低于此值时，提示模型考虑转人工。 */
        private double hintMinConfidence = 0.6;
        /** 提示模型的档位期望值门槛（2 = 明显不满）。 */
        private double hintMinScore = 2.0;
    }

    /**
     * 工具组按意图收窄：只影响本轮模型看得到哪些工具，不改会话里持久化的激活组。
     *
     * <p>判错的代价是「这一轮办不了事」，所以门槛取官方建议的自动执行线 0.9。
     * 转人工组与未分组的基础工具永远保留。</p>
     */
    @Data
    public static class ToolScope {
        private boolean enabled = true;
        private double minConfidence = 0.9;
        /** 意图 → 本轮保留的工具组。未列出的意图（含 other）不收窄。 */
        private Map<String, List<String>> intentGroups = defaultIntentGroups();
    }

    /**
     * 答复安全闸门：关键词没命中时，用语义判定补拦「声称资金已到账」的表述。
     *
     * <p>误拦的代价是给正常回复追加一句否定澄清并转人工，比较重，故取官方建议的高阈值区间。</p>
     */
    @Data
    public static class PaymentClaim {
        private boolean enabled = true;
        private double minProbability = 0.8;
    }

    /**
     * 语义缓存写入判定：正则放行之后再问一次「这组问答是否只适用于特定用户」。
     *
     * <p>漏判的代价是把 A 用户的信息返回给 B，远重于少缓存一条，故阈值取低位：
     * 概率达到此值就不写缓存。</p>
     */
    @Data
    public static class CacheGuard {
        private boolean enabled = true;
        private double maxProbability = 0.3;
    }

    /**
     * 退款风险：退款本就 100% 走人工审批，这里只在文本出现高风险信号时额外触发坐席介入。
     *
     * <p>漏报（高风险退款只躺在审批队列里没人管）比误报（坐席多看一眼）代价高，阈值取中位。
     * 注意 Jev 只看得到对话文本，看不到交易与账户风控数据，它不是风控系统。</p>
     */
    @Data
    public static class RefundRisk {
        private boolean enabled = true;
        private double minProbability = 0.6;
        /** 视为「发起退款」的工具名。 */
        private List<String> refundTools = new ArrayList<>(List.of("submitRefund"));
    }

    private static Map<String, String> defaultIntentCriteria() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put("presale", "售前咨询：商品信息、推荐、库存、优惠活动");
        criteria.put("order", "查询订单状态、物流轨迹、发货、签收、修改或取消订单");
        criteria.put("refund", "申请退款、退货、换货，或咨询售后资格与进度");
        criteria.put("complaint", "投诉、差评、对服务或商品表达不满并要求处理");
        criteria.put("consult", "咨询发票、运费、保修、无理由退换等政策规则");
        criteria.put("other", "以上都不是，或同时涉及多类、无法判断");
        return criteria;
    }

    /**
     * 默认映射：每个意图额外保留知识库组，因为几乎所有业务问题都可能需要查政策。
     * 退款与投诉带上订单组，是因为它们几乎总要先定位订单。
     */
    private static Map<String, List<String>> defaultIntentGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("presale", new ArrayList<>(List.of("presale", "knowledge")));
        groups.put("order", new ArrayList<>(List.of("order", "knowledge")));
        groups.put("refund", new ArrayList<>(List.of("after_sales", "order", "knowledge")));
        groups.put("complaint", new ArrayList<>(List.of("complaint", "order", "after_sales", "knowledge")));
        groups.put("consult", new ArrayList<>(List.of("knowledge")));
        return groups;
    }
}
