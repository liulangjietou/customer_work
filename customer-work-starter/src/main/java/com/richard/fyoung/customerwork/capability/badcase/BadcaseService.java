package com.richard.fyoung.customerwork.capability.badcase;

import com.richard.fyoung.customerwork.capability.eval.EvalCaseSource;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * badcase 回流服务——把"记录下来的失败"变成"改进过的系统"。
 *
 * <p>此前负反馈与质检失败只写进 {@code cw_fact_log} 就结束了，{@code FeedbackService} 的注释里
 * 明确写着"诚实边界：只记录，不自动回流知识库"。缺的就是本类：一条 badcase 从待筛选出发，
 * 走到两个出口——<b>补知识库</b>（下次能答对）和<b>加评测用例</b>（下次答错立刻被发现）。
 * 这两个出口不互斥，一条值得处理的 badcase 通常两件事都该做。</p>
 *
 * <p><b>刻意不做自动回流</b>：模型答错的原因千差万别（知识缺失、检索没召回、话术不当、用户表述歧义），
 * 自动把用户的负反馈灌进知识库，等于让最不满的那批用户直接改写知识——这是投毒面。
 * 人工筛选这一步是必要的，本类要做的是把这一步的成本降到"看两眼点一下"。</p>
 *
 * <p>记录动作（{@link #record}）是旁路：失败只记日志，绝不阻断用户提交反馈或质检流程；
 * 而采纳/忽略是运营的主动操作，失败必须抛出——静默失败会让人以为处理过了，同一条被反复翻出来。</p>
 *
 * <p>两个协作者<b>可空</b>而不是用 {@code ObjectProvider}：聊天留痕与知识库后端都是可选能力
 * （各自的 store-mode 决定装没装），而本类还要能在后台侧用跨库 Mapper 直接组装出来——
 * 那里没有 Spring 容器可供惰性查找。可空字段让两种装配路径共用同一个构造器。</p>
 * @author owlzhangfq@gmail.com
 */
public class BadcaseService {

    private static final Logger log = LoggerFactory.getLogger(BadcaseService.class);

    /** 回查对话上下文时往前看的消息条数：够覆盖一问一答及少量穿插的系统消息。 */
    private static final int CONTEXT_LOOKBACK = 20;

    /** 回流生成的知识条目的来源标注，便于日后审计"这条知识哪来的"。 */
    private static final String KNOWLEDGE_SOURCE_PREFIX = "badcase:";

    private final BadcaseStore store;
    private final EvalCaseStore evalCaseStore;

    /** 可空：聊天留痕未开启时无法回查对话上下文，badcase 仍照常登记。 */
    private final ChatMessageStore chatStore;

    /** 可空：知识库非 jdbc 后端时无法回流知识条目，转评测用例这条路仍可走。 */
    private final KnowledgeMapper knowledgeMapper;

    public BadcaseService(BadcaseStore store,
                          EvalCaseStore evalCaseStore,
                          ChatMessageStore chatStore,
                          KnowledgeMapper knowledgeMapper) {
        this.store = store;
        this.evalCaseStore = evalCaseStore;
        this.chatStore = chatStore;
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 记录一条 badcase（旁路，失败不阻断主链路）。
     *
     * <p>会尝试从聊天留痕回查"用户问了什么、AI 答了什么"：只给运营一个 messageId，
     * 筛选界面就没法用——没人能凭一串 ID 判断该不该回流。</p>
     *
     * @return 记录成功返回 badcase，失败返回空
     */
    public Optional<Badcase> record(BadcaseSource source, String sessionId, String messageId, String detail) {
        try {
            DialogContext context = resolveContext(sessionId, messageId);
            Badcase badcase = new Badcase(UUID.randomUUID().toString(), source, sessionId, messageId,
                context.userInput(), context.agentReply(), detail, System.currentTimeMillis());
            store.save(badcase);
            log.info("badcase recorded: id={}, source={}, sessionId={}",
                badcase.getId(), source, sessionId);
            return Optional.of(badcase);
        } catch (Exception e) {
            log.error("record badcase failed, errorCode={}, sessionId={}, source={}",
                "BADCASE-RECORD-FAIL", sessionId, source, e);
            return Optional.empty();
        }
    }

    /** 按条件查询待筛选队列。 */
    public List<Badcase> query(BadcaseQuery query) {
        return store.query(query);
    }

    /** 按条件计数（分页总数与"待筛 N 条"角标共用）。 */
    public long count(BadcaseStatus status, BadcaseSource source) {
        return store.count(status, source);
    }

    /** 按 ID 查一条。 */
    public Optional<Badcase> find(String badcaseId) {
        return store.find(badcaseId);
    }

    /**
     * 采纳为知识库条目：把答错的那块知识补上。
     *
     * <p>标题、内容、关键词由运营填写而非从原文照抄——用户的原始提问是口语化的，
     * 直接当知识条目会污染检索质量。</p>
     *
     * @throws IllegalStateException 知识库未走 jdbc、badcase 不存在或已采纳过时
     */
    public Badcase adoptAsKnowledge(String badcaseId, String title, String content,
                                    String keyword, String operator) {
        Badcase badcase = require(badcaseId);
        if (knowledgeMapper == null) {
            throw new IllegalStateException(
                "cannot adopt as knowledge: knowledge backend is not jdbc-backed");
        }
        KnowledgeDO entry = new KnowledgeDO();
        entry.setTitle(title);
        entry.setContent(content);
        entry.setKeyword(keyword);
        entry.setSource(KNOWLEDGE_SOURCE_PREFIX + badcaseId);
        knowledgeMapper.insert(entry);

        badcase.adoptAsKnowledge(entry.getId(), operator, System.currentTimeMillis());
        store.save(badcase);
        log.info("badcase adopted as knowledge: id={}, knowledgeId={}, operator={}",
            badcaseId, entry.getId(), operator);
        return badcase;
    }

    /**
     * 采纳为评测用例：把这次翻车固化成回归防护。
     *
     * @param caseId   用例编号（运营指定，同类型内唯一）
     * @param evalType 归入哪类评测
     * @param expected INTENT 传期望意图（空表示期望快车道不命中）；QUALITY 传期望要点
     * @throws IllegalStateException badcase 不存在、已采纳过，或用例编号已被占用时
     */
    public Badcase adoptAsEvalCase(String badcaseId, String caseId, EvalType evalType,
                                   String expected, String category, String operator) {
        Badcase badcase = require(badcaseId);
        if (!StringUtils.hasText(badcase.getUserInput())) {
            throw new IllegalStateException(
                "cannot adopt as eval case: user input unavailable for badcase " + badcaseId);
        }
        // 编号冲突会静默覆盖掉一条已有用例（upsert 语义），必须提前拦
        if (evalCaseStore.find(evalType, caseId).isPresent()) {
            throw new IllegalStateException("eval case id already exists: " + caseId);
        }
        evalCaseStore.save(new PersistedEvalCase(caseId, evalType, badcase.getUserInput(),
            expected, category, EvalCaseSource.BADCASE, true, badcaseId, System.currentTimeMillis()));

        badcase.adoptAsEvalCase(caseId, operator, System.currentTimeMillis());
        store.save(badcase);
        log.info("badcase adopted as eval case: id={}, caseId={}, evalType={}, operator={}",
            badcaseId, caseId, evalType, operator);
        return badcase;
    }

    /** 忽略：噪声反馈或质检误报。 */
    public Badcase ignore(String badcaseId, String reason, String operator) {
        Badcase badcase = require(badcaseId);
        badcase.ignore(operator, reason, System.currentTimeMillis());
        store.save(badcase);
        log.info("badcase ignored: id={}, operator={}", badcaseId, operator);
        return badcase;
    }

    private Badcase require(String badcaseId) {
        return store.find(badcaseId)
            .orElseThrow(() -> new IllegalStateException("badcase not found: " + badcaseId));
    }

    /**
     * 从聊天留痕回查这条 badcase 对应的一问一答。
     *
     * <p>取不到就返回空上下文而不是抛错：聊天留痕是可选能力（{@code chat-log.store-mode}），
     * 没开时 badcase 仍该被记下来，只是筛选时得靠 sessionId 去别处翻。</p>
     */
    private DialogContext resolveContext(String sessionId, String messageId) {
        if (chatStore == null || !StringUtils.hasText(sessionId)) {
            return DialogContext.empty();
        }
        // findBySession 返回按 id 升序的一页，最新的在末尾
        List<ChatMessage> messages = chatStore.findBySession(sessionId, null, CONTEXT_LOOKBACK);
        int replyIndex = locateReply(messages, messageId);
        if (replyIndex < 0) {
            return DialogContext.empty();
        }
        String agentReply = messages.get(replyIndex).content();
        // 机器人回复往前找最近一条用户消息，中间可能穿插系统消息
        for (int i = replyIndex - 1; i >= 0; i--) {
            if (messages.get(i).senderType() == TicketActorType.USER) {
                return new DialogContext(messages.get(i).content(), agentReply);
            }
        }
        return new DialogContext(null, agentReply);
    }

    /** 定位被反馈的那条回复：给了 messageId 就精确匹配，没给（质检来源）则取最后一条机器人回复。 */
    private int locateReply(List<ChatMessage> messages, String messageId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (StringUtils.hasText(messageId)) {
                if (messageId.equals(message.messageId())) {
                    return i;
                }
            } else if (message.senderType() == TicketActorType.BOT) {
                return i;
            }
        }
        return -1;
    }

    /** 回查到的对话上下文。 */
    private record DialogContext(String userInput, String agentReply) {
        static DialogContext empty() {
            return new DialogContext(null, null);
        }
    }
}
