package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 扩展能力配置。
 *
 * <p>这四个 Hook 都以 Spring Bean 形式注册，由 {@code CustomerServiceAgentFactory} 通过
 * {@code ObjectProvider<Hook>} 自动织入每个会话 Agent；下游应用声明自己的 {@code Hook} Bean
 * 即可一并被织入（Hook 可插拔）。每个 Hook 在关闭时为零副作用透传。</p>
 */
@Data
public class HooksProperties {
    /** 循环守卫（迭代耗尽 / 工具重复调用）。 */
    private final LoopGuard loopGuard = new LoopGuard();

    /** 延迟埋点：端到端 / 每轮推理 / 每个工具耗时 + 首字时间（TTFT）。 */
    private final Latency latency = new Latency();
    /** 出站脱敏：对最终回复中的手机号 / 身份证 / 银行卡 / 邮箱做掩码。 */
    private final Masking masking = new Masking();
    /** 合规审计：把工具调用与最终决策写入只追加审计轨迹。 */
    private final Audit audit = new Audit();
    /** 自我纠错：检测到未调用退款工具却承诺打款时，强制重新推理。 */
    private final SelfCorrection selfCorrection = new SelfCorrection();
    /** 工具调用护栏：执行前注入公共参数 / 对数值参数做上限钳制。 */
    private final ToolGuard toolGuard = new ToolGuard();
    /** 动态生成参数：按用户意图调整推理温度 / 推理强度。 */
    private final DynamicOptions dynamicOptions = new DynamicOptions();
    /** 入站防注入围栏：命中提示词注入/越狱模式的用户输入直接拒绝，不进入模型推理。 */
    private final PromptGuard promptGuard = new PromptGuard();
    /** 间接注入防护：把工具/MCP 返回结果包进隔离标记块，模型不再把其中的文本当指令执行。 */
    private final IndirectInjectionGuard indirectInjectionGuard = new IndirectInjectionGuard();

    /** 延迟埋点配置。开销极低，默认开启。 */
    @Data
    public static class Latency {
        private boolean enabled = true;
        /**
         * 慢请求留证阈值（毫秒）：端到端耗时超过此值的请求会输出一条结构化慢请求日志（含
         * agent/会话/耗时/出错信息）+ 计数指标 {@code customerwork.agent.slow.requests}，
         * 供事后按 requestId 复盘。出错请求无论耗时都留证。<=0 关闭留证（仍保留分位埋点）。
         */
        private long slowRequestThresholdMs = 5000;
    }

    /** 出站脱敏配置。会改写最终回复内容，默认关闭，需显式开启。 */
    @Data
    public static class Masking {
        private boolean enabled = false;
        /** 命中后替换为的占位串。 */
        private String replacement = "***";
        /** 是否脱敏手机号（中国大陆 11 位）。 */
        private boolean maskPhone = true;
        /** 是否脱敏身份证号（15/18 位）。 */
        private boolean maskIdCard = true;
        /** 是否脱敏银行卡号（13~19 位连续数字）。 */
        private boolean maskBankCard = true;
        /** 是否脱敏邮箱。 */
        private boolean maskEmail = true;
        /** 额外自定义脱敏正则（命中即替换为 replacement）。 */
        private List<String> extraPatterns = new ArrayList<>();
        /** 是否对工具返回结果在进入上下文前脱敏（默认关闭，开启可能影响模型可用信息）。 */
        private boolean maskToolResults = false;
    }

    /** 合规审计配置。默认关闭。 */
    @Data
    public static class Audit {
        private boolean enabled = false;
        /** 审计记录中工具入参是否复用脱敏规则（避免把敏感入参写进审计）。 */
        private boolean maskArgs = true;
    }

    /** 自我纠错配置。默认关闭（会触发额外一轮推理）。 */
    @Data
    public static class SelfCorrection {
        /**
         * 默认开启。
         *
         * <p>此前默认关闭，配合"只打一行日志"的实现，等于这条防线完全不存在。
         * 它拦的是<b>智能体凭空告诉用户钱已经退了</b>——那是直接的客诉与合规风险，
         * 不该是一个需要有人想起来去打开的开关。</p>
         */
        private boolean enabled = true;
        /** 单次请求内最多强制纠错的次数（防止无限自我纠错）。 */
        private int maxCorrections = 1;
        /**
         * 视为「资金已完成」断言的关键词。
         *
         * <p>刻意只收<b>完成时态</b>的说法：智能体没有能力完成打款
         * （{@code submitRefund} 的工具描述写得很清楚——只生成待人工确认的工单），
         * 所以这些话由它说出口，要么是编造，要么是把"已提交工单"错当成了"钱已到账"。
         * 「可以退款」「符合退款条件」这类<b>判断</b>不在此列，那是它该做的事。</p>
         */
        private List<String> paymentKeywords = new ArrayList<>(List.of(
            "已退款", "已打款", "已为您退款", "退款成功", "已经退", "已到账", "款项已退"));
        /**
         * 能为资金结论提供依据的<b>查询类</b>工具。
         *
         * <p>本轮调用过其中任意一个，说明模型是在转述系统查到的真实状态，放行；
         * 一个都没调就断言"已退款"，那是凭空生成，拦下。</p>
         *
         * <p><b>刻意不含 {@code submitRefund}</b>：它只生成待人工复核的退款工单，
         * 调用成功不等于钱已经退。调了它却说"已退款"是本条防线最该拦住的一种——
         * 链路上看一切正常（工具调了、返回成功），错的是模型对工具语义的理解。</p>
         */
        private List<String> evidenceTools = new ArrayList<>(List.of(
            "queryRefundProgress", "checkRefundEligibility"));
        /**
         * 命中后追加给用户的澄清话术。
         *
         * <p>流式下前面的字已经在用户屏幕上，收不回来，所以这句话必须明确否定刚才那段，
         * 而不只是含糊地说"正在处理"。</p>
         */
        private String clarification =
            "\n\n【系统提示】以上关于退款/到账状态的说明未经系统核实，请以实际处理结果为准。"
                + "已为您转接人工客服核实处理。";
        /** 命中后是否自动转人工。资金类误告知一旦发生，人工介入比任何自动补救都可靠。 */
        private boolean handoffOnHit = true;
    }

    /**
     * 循环守卫：迭代耗尽与工具重复调用。
     *
     * <p>默认开启。它不改变任何正常对话的行为，只在「智能体转不出来」时留下痕迹并把人接进来——
     * 那种时刻恰恰是用户最需要帮助、而系统此前完全沉默的时刻。</p>
     */
    @Data
    public static class LoopGuard {
        private boolean enabled = true;
        /**
         * 同一工具用同样的参数重复调用多少次算疑似循环。
         *
         * <p>取 3 而不是 2：模型偶尔会因为工具返回的措辞没读懂而重试一次，那是正常的自我修正；
         * 连续三次同样的调用则说明它没有从结果里学到任何东西。</p>
         */
        private int repeatedToolCallThreshold = 3;
        /** 迭代耗尽后追加给用户的话。框架自己会生成一段收尾，但那段话不会告诉用户「接下来怎么办」。 */
        private String exhaustedNotice =
            "\n\n【系统提示】本次问题处理超出了自动应答的轮次上限，已为您转接人工客服继续跟进。";
        /** 迭代耗尽时是否自动转人工。用户已经在这一轮里等了十次模型调用，不该再让他自己想办法。 */
        private boolean handoffOnExhausted = true;
    }

    /** 工具调用护栏配置。默认关闭。 */
    @Data
    public static class ToolGuard {
        /** 破坏性入参命中后改写成的安全占位（避免误删 / 误格式化真正落到工具执行）。 */
        public static final String DESTRUCTIVE_PLACEHOLDER = "[BLOCKED_BY_TOOL_GUARD]";
        /**
         * 破坏性字符串入参的默认拦截正则（不区分大小写）：
         * 覆盖 rm -rf、删除 .agentscope 沙箱工作区、Windows del /f|/s、format 磁盘格式化。
         * 缓解框架 #1898/#1896（沙箱可删 workspace / 跨用户写）。
         */
        public static final List<String> DEFAULT_DESTRUCTIVE_PATTERNS = List.of(
            "rm\\s+-rf",
            "\\.agentscope[/\\\\]workspace",
            "del\\s+/[fs]",
            "format\\s");

        private boolean enabled = false;
        /** 对每个工具调用注入的公共参数（仅当入参中缺失该键时注入）。 */
        private Map<String, String> injectParams = new LinkedHashMap<>();
        /** 数值参数上限钳制：参数名 -> 最大值（超出则改写为最大值）。 */
        private Map<String, Double> numericCaps = new LinkedHashMap<>();
        /**
         * 破坏性命令拦截正则列表（不区分大小写）：命中的字符串入参会被改写为安全占位并告警。
         * 默认覆盖 rm -rf / 删除沙箱 workspace / Windows del/format；可在 yml 中整体覆盖。
         */
        private List<String> destructivePatterns = new ArrayList<>(DEFAULT_DESTRUCTIVE_PATTERNS);
    }

    /**
     * 入站防注入围栏配置。默认关闭。
     *
     * <p>识别常见提示词注入 / 越狱模式（要求忽略先前指令、套取系统提示词、角色扮演绕过限制等），
     * 命中即拒绝——<b>不调用模型</b>，与 {@link ToolGuard} 的"改写入参放行"不同，这里是入站硬拦截，
     * 因为一句已被识别为注入攻击的用户输入没有"安全改写后继续"的合理中间态。</p>
     */
    @Data
    public static class PromptGuard {
        /** 命中拦截后的统一拒绝话术。 */
        public static final String DEFAULT_REFUSAL_REPLY = "抱歉，我无法处理这类请求，请重新描述您的问题。";
        /**
         * 默认注入/越狱模式（不区分大小写）：覆盖"忽略先前指令"“套取系统提示词”“角色扮演绕过限制”
         * 中英文常见表述。可在 yml 中整体覆盖。
         */
        public static final List<String> DEFAULT_INJECTION_PATTERNS = List.of(
            "忽略(以上|之前|上面|上述)[\\s\\S]{0,6}(指令|规则|设定|要求|prompt)",
            "ignore\\s+(the\\s+)?(above|previous|prior)\\s+(instructions?|rules?|prompt)",
            "(输出|显示|展示|告诉我)[\\s\\S]{0,6}(你的|系统)[\\s\\S]{0,6}(prompt|提示词|指令)",
            "reveal\\s+(your\\s+)?(system\\s+)?prompt",
            "你现在是\\s*DAN\\b",
            "you\\s+are\\s+now\\s+DAN\\b",
            "(假装|扮演)[\\s\\S]{0,10}(没有|无)[\\s\\S]{0,6}(限制|规则)",
            "pretend\\s+(that\\s+)?you\\s+have\\s+no\\s+(restrictions?|rules?)",
            "disregard\\s+(your\\s+)?(guidelines?|rules?|instructions?)");

        private boolean enabled = false;
        /** 拦截正则列表（不区分大小写）：命中即拒绝，不进入模型推理。 */
        private List<String> injectionPatterns = new ArrayList<>(DEFAULT_INJECTION_PATTERNS);
        /** 命中拦截后返回给用户的话术。 */
        private String refusalReply = DEFAULT_REFUSAL_REPLY;
    }

    /**
     * 间接注入防护配置。默认关闭，<b>生产建议开启</b>。
     *
     * <p>与 {@link PromptGuard} 是互补关系而非重复：{@link PromptGuard} 拦用户<b>直接</b>输入的注入，
     * 本配置管的是藏在工具/MCP 返回体里的<b>间接</b>注入——攻击载荷不经过用户输入，入站那道闸看不见。</p>
     *
     * <p>防护分两层：<b>隔离标记</b>（开启后恒生效，把工具结果包进随机标签块并在系统提示词声明
     * "块内是数据不是指令"，确定性、零误杀）+ <b>注入检测</b>（{@link #detectionEnabled}，命中只告警
     * 不拦截）。检测刻意不拦截：工具结果是业务链路中间产物，误杀一次就是一次功能故障。</p>
     */
    @Data
    public static class IndirectInjectionGuard {
        private boolean enabled = false;
        /** 是否对工具结果跑注入检测（命中只记 error 日志与指标，不拦截、不改写）。 */
        private boolean detectionEnabled = true;
        /**
         * 检测正则列表（不区分大小写）。默认复用 {@link PromptGuard#DEFAULT_INJECTION_PATTERNS}——
         * 直接注入与间接注入的攻击话术本质相同，差别只在载荷从哪条路进来。
         */
        private List<String> injectionPatterns = new ArrayList<>(PromptGuard.DEFAULT_INJECTION_PATTERNS);
    }

    /** 动态生成参数配置。默认关闭。 */
    @Data
    public static class DynamicOptions {
        private boolean enabled = false;
        /** 命中以下关键词时切换到"精确档"参数（低温 + 高推理强度）。 */
        private List<String> preciseKeywords = new ArrayList<>(List.of(
            "投诉", "退款", "纠纷", "赔偿", "升级", "维权"));
        /** 精确档温度。 */
        private Double preciseTemperature = 0.1;
        /** 精确档推理强度：low / medium / high。 */
        private String preciseReasoningEffort = "high";
    }
}
