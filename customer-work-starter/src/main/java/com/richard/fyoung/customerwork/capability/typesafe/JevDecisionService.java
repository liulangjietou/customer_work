package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.core.constant.MetricTags;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jev 决策服务：所有决策点的问题定义、调用与计量都收在这里。
 *
 * <p><b>问题文案集中在本类</b>（官方 agent-skill 的建议：把问题与阈值放在一处便于审阅）。
 * 阈值在 {@link TypeSafeProperties}，判定逻辑在决策对象自身（{@link TurnDecision}、{@link NoulVerdict}）。</p>
 *
 * <p><b>同一份代码服务线上与后台影子</b>：后台展示的判定必须与线上完全一致，
 * 两边各写一份等价逻辑，迟早会出现「后台看到的和线上不一样」。</p>
 *
 * <p>所有方法失败时返回 {@link Mono#empty()}（传输层已记日志与指标），调用方据此走原路径。</p>
 */
public class JevDecisionService {

    /** 问题编号。除意图外都直接用决策点编码：它们本就是「为某个决策点提的那个问题」。 */
    static final String Q_INTENT = "intent";
    static final String Q_ESCALATION = JevDecisionEvent.POINT_ESCALATION;
    static final String Q_PAYMENT_CLAIM = JevDecisionEvent.POINT_PAYMENT_CLAIM;
    static final String Q_PERSONAL_CONTEXT = JevDecisionEvent.POINT_CACHE_GUARD;
    static final String Q_REFUND_RISK = JevDecisionEvent.POINT_REFUND_RISK;

    static final String M_DECISIONS = "customerwork.typesafe.decisions";
    /** 与决策事件载荷里的 point 字段不是一回事：这是监控查询的维度名。 */
    private static final String TAG_POINT = "decision_point";
    private static final String TAG_MODE = "run_mode";

    private static final String INTENT_INSTRUCTIONS =
        "判断这条客服消息的用户意图，选择最匹配的一项；同时涉及多类或无法判断时选 other。";

    private static final String ESCALATION_INSTRUCTIONS = "评估这条客服消息中用户的情绪强度与转人工意愿。";

    /** 从低到高排列，下标即档位编号；最高档是「自动转人工」的判定对象。 */
    static final List<String> ESCALATION_LEVELS = List.of(
        "情绪平稳，正常咨询",
        "轻微不满或着急",
        "明显不满、抱怨，或反复提出同一诉求",
        "强烈愤怒，或明确要求转人工、要求投诉升级");

    private static final SystemOneQuestion PAYMENT_CLAIM = new SystemOneQuestion.Noul(
        "这段客服回复是否在向用户断言退款或款项已经完成、已到账、已原路退回？",
        "明确告诉用户钱已经退了、已到账或已退回",
        "只说已提交申请、正在处理、等待审核，或与退款到账无关");

    private static final SystemOneQuestion PERSONAL_CONTEXT = new SystemOneQuestion.Noul(
        "这组问答的答案是否只适用于提问的这一位用户？",
        "答案依赖某个具体用户的订单、账户、物流、身份或个人情况，换一个人问答案就不同",
        "通用的政策或规则说明，任何人问都是同样的答案");

    private static final SystemOneQuestion REFUND_RISK = new SystemOneQuestion.Noul(
        "这次退款请求是否存在需要人工坐席立即介入的风险信号？",
        "出现威胁、辱骂、欺诈或套现话术、疑似冒用他人账户、金额与描述明显不符、情绪激烈等",
        "正常的退款诉求，描述清晰、情绪平稳");

    private static final String CTX_TURN = "typesafe.turn.decision";

    private final SystemOneClient client;
    private final TypeSafeProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * @param meterRegistry 可为 null
     */
    public JevDecisionService(SystemOneClient client, TypeSafeProperties properties, MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public TypeSafeProperties properties() {
        return properties;
    }

    /**
     * 本轮入站决策，结果缓存进 {@link RuntimeContext}：ReAct 循环里每迭代一次都会经过中间件，
     * 且情绪与工具收窄两个决策点共用同一次调用，缓存保证一轮只付一次代价。
     *
     * <p>缓存的是 {@code Mono.cache()} 而不是结果：即便两个调用方并发取，也只会发一次请求。</p>
     */
    public Mono<TurnDecision> decideTurn(RuntimeContext ctx, String userText) {
        if (ctx == null) {
            return decideTurn(userText);
        }
        TurnHolder cached = ctx.get(CTX_TURN, TurnHolder.class);
        if (cached != null) {
            return cached.decision();
        }
        Mono<TurnDecision> decision = decideTurn(userText).cache();
        ctx.put(CTX_TURN, TurnHolder.class, new TurnHolder(decision));
        return decision;
    }

    /** 无运行时上下文的入站决策（{@code /consult} 编排器自建上下文，不与主链路共享）。 */
    public Mono<TurnDecision> decideTurn(String userText) {
        if (!StringUtils.hasText(userText)) {
            return Mono.empty();
        }
        Map<String, SystemOneQuestion> questions = new LinkedHashMap<>();
        questions.put(Q_INTENT, new SystemOneQuestion.Choice(INTENT_INSTRUCTIONS, properties.getIntentCriteria()));
        questions.put(Q_ESCALATION, new SystemOneQuestion.Score(ESCALATION_INSTRUCTIONS, ESCALATION_LEVELS));
        return client.ask(userText, questions, turnTimeout())
            .map(result -> TurnDecision.from(result, Q_INTENT, Q_ESCALATION));
    }

    /** 升级倾向的最高档编号。 */
    public int escalationTopLevel() {
        return ESCALATION_LEVELS.size() - 1;
    }

    /** 某个档位的描述，供展示。 */
    public String escalationLevelLabel(int level) {
        return level >= 0 && level < ESCALATION_LEVELS.size() ? ESCALATION_LEVELS.get(level) : String.valueOf(level);
    }

    /** 回复是否在断言资金已到账。 */
    public Mono<NoulVerdict> judgePaymentClaim(String reply) {
        return noul(reply, Q_PAYMENT_CLAIM, PAYMENT_CLAIM);
    }

    /** 问答对是否只适用于特定用户（决定能否进语义缓存）。 */
    public Mono<NoulVerdict> judgePersonalContext(String question, String answer) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(answer)) {
            return Mono.empty();
        }
        return noul("用户问：" + question + "\n客服答：" + answer, Q_PERSONAL_CONTEXT, PERSONAL_CONTEXT);
    }

    /** 退款请求是否带有需要坐席立即介入的风险信号。 */
    public Mono<NoulVerdict> judgeRefundRisk(String userText, String amount, String reason) {
        String state = "用户原话：" + nullToEmpty(userText) + "\n退款金额：" + nullToEmpty(amount)
            + "\n退款原因：" + nullToEmpty(reason);
        return noul(state, Q_REFUND_RISK, REFUND_RISK);
    }

    /** 记录一次决策落到了哪个动作，按决策点与运行模式分桶。 */
    public void recordDecision(String point, String result, JevRunMode mode) {
        if (meterRegistry != null) {
            meterRegistry.counter(M_DECISIONS, TAG_POINT, point, MetricTags.RESULT, result,
                TAG_MODE, mode.name().toLowerCase()).increment();
        }
    }

    private Mono<NoulVerdict> noul(String state, String questionId, SystemOneQuestion question) {
        if (!StringUtils.hasText(state)) {
            return Mono.empty();
        }
        return client.ask(state, Map.of(questionId, question), safetyTimeout())
            .flatMap(result -> Mono.justOrEmpty(result.noul(questionId)
                .map(answer -> new NoulVerdict(answer.probability(), result.model(), result.latencyMs()))));
    }

    /**
     * 入站决策含情绪升级（安全类）时用安全类超时，只做工具收窄（优化类）时用短超时——
     * 两个决策点共用一次调用，优化类搭安全类的便车，不再额外拖慢对话。
     */
    private Duration turnTimeout() {
        return Duration.ofMillis(properties.getEscalation().isEnabled()
            ? properties.getTimeoutMs() : properties.getFastTimeoutMs());
    }

    private Duration safetyTimeout() {
        return Duration.ofMillis(properties.getTimeoutMs());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 放进 RuntimeContext 的缓存载体（其 API 按类型取值，需要一个具名类型）。 */
    record TurnHolder(Mono<TurnDecision> decision) {
    }
}
