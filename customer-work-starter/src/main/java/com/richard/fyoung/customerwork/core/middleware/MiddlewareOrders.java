package com.richard.fyoung.customerwork.core.middleware;

/**
 * 治理中间件的执行顺序契约。
 *
 * <p><b>框架语义</b>（{@code MiddlewareBase#order()} 的 javadoc）：<b>数值越大优先级越高、
 * 位置越靠外</b>；相同数值时保持 builder 注册顺序；默认值是 {@code 1}。
 * "靠外"意味着<b>入站时先执行、出站时后执行</b>。</p>
 *
 * <p><b>为什么必须集中声明</b>：在此之前全部 24 个中间件都用默认值 {@code 1}，也就是说实际顺序
 * 完全取决于 Spring 把 Bean 交给 {@code orderedStream()} 的先后——而它们一个 {@code @Order}
 * 都没标，那个先后是<b>不确定的</b>。于是"先裁剪还是先注入""审计记的是脱敏前还是脱敏后"
 * 这类问题，答案由 Bean 定义顺序偶然决定，改一行无关代码就可能翻转，而且不报任何错。</p>
 *
 * <p>本项目已经在同构的问题上栽过一次：{@code SubjectQuotaWebFilter} 的 Order 必须排在两个
 * 鉴权过滤器之后，抢在前面会把登录用户全按匿名 IP 限流。中间件侧的对称风险是
 * {@code MaskingMiddleware} 与 {@code AuditMiddleware} 的相对位置——排错了，
 * 未脱敏的手机号与订单号就会进审计留痕。</p>
 *
 * <p><b>分层依据</b>（从外到内）：</p>
 * <ol>
 *   <li><b>准入</b>：撤销态必须在进入任何链路之前拦下；</li>
 *   <li><b>观测与计量</b>：要覆盖内层全部耗时与 token，因此必须比被观测的东西更外；</li>
 *   <li><b>审计</b>：记录的应当是最终真正发出的内容，因此比脱敏与过滤更外（出站时后执行）；</li>
 *   <li><b>入站防护</b>：恶意输入越早挡住越好；</li>
 *   <li><b>出站过滤</b>：先脱敏再过违规词，这样敏感词命中记录里存的片段已经是脱敏过的；</li>
 *   <li><b>工具治理</b>：授权、审批、入参护栏，都要在工具真正执行之前；</li>
 *   <li><b>上下文组装</b>：最靠近模型。预算裁剪必须是最内层——它要在知识注入、
 *       记忆召回、阶段提示词全部完成之后才算得准，否则内层还会继续往上下文里加东西。</li>
 * </ol>
 *
 * <p>新增中间件时在这里插一个值并说明它为什么在那一层；
 * {@code MiddlewareOrderContractTest} 会断言不留默认值、不重复取值、关键相对次序成立。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class MiddlewareOrders {

    // ---------- 准入 ----------

    /** 撤销态不允许进入模型、MCP、Skill 或任何其它工具链，必须最先判定。 */
    public static final int AGENT_LIFECYCLE = 200;

    // ---------- 观测与计量 ----------

    /** 请求/工具/错误打点，需覆盖内层全链路。 */
    public static final int OBSERVABILITY = 190;

    /** 租户上下文：内层所有落库、配额、审计与命中记录都依赖它，缺了持久层 fail-closed。 */
    public static final int TENANT_CONTEXT = 180;

    /** token 用量的唯一落点，必须看到完整的一轮。 */
    public static final int AGENT_CALL_TIMING = 170;

    /** 终止原因与真实 token 用量采集。 */
    public static final int CHAT_TERMINAL_CAPTURE = 165;

    /** 耗时统计，需包含内层全部耗时。 */
    public static final int LATENCY = 160;

    /** 模型调用回放快照。 */
    public static final int MODEL_REPLAY_CAPTURE = 155;

    // ---------- 审计 ----------

    /** 审计留痕：出站时最后执行，记录的是已脱敏、已过滤的最终内容。 */
    public static final int AUDIT = 150;

    // ---------- 入站防护 ----------

    /** 直接提示词注入防护：恶意输入越早挡住越好。 */
    public static final int PROMPT_INJECTION_GUARD = 140;

    /** 间接注入防护：针对工具结果与外部内容。 */
    public static final int INDIRECT_INJECTION_GUARD = 135;

    // ---------- 出站过滤 ----------

    /** 敏感词：入站拦截 + 出站过滤。比脱敏更外，因此命中记录里的片段已经是脱敏过的。 */
    public static final int SENSITIVE_WORD = 130;

    /** PII 脱敏：出站方向最先执行，把个人信息挡在后续所有留痕之前。 */
    public static final int MASKING = 125;

    // ---------- 工具治理 ----------

    /** 主体级工具授权判定，必须在工具执行之前。 */
    public static final int SUBJECT_TOOL_AUTHORIZATION = 120;

    /** 工具级人工确认。 */
    public static final int HUMAN_APPROVAL = 115;

    /** 工具入参护栏：公共参数注入、数值钳制、破坏性命令改写。 */
    public static final int TOOL_GUARD = 110;

    // ---------- 内容质量 ----------

    /** 模型输出的自我纠错与越权承诺检测。 */
    public static final int SELF_CORRECTION = 100;

    // ---------- 上下文组装（靠近模型） ----------

    /** 按对话阶段动态组装系统提示词。 */
    public static final int DIALOG_STAGE = 80;

    /** RAG 知识的瞬态注入。 */
    public static final int KNOWLEDGE_INJECTION = 70;

    /** 动态模型参数。 */
    public static final int DYNAMIC_OPTIONS = 60;

    /**
     * 上下文预算裁剪：<b>必须是最内层</b>。
     *
     * <p>它要在知识注入、长期记忆召回、阶段提示词全部完成之后才算得准；
     * 排在它们外面的话，裁完之后内层还会继续往上下文里加东西，预算就失去意义。</p>
     */
    public static final int CONTEXT_BUDGET = 50;

    private MiddlewareOrders() {
    }
}
