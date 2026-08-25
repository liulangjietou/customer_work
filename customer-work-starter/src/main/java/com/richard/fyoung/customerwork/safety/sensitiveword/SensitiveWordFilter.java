package com.richard.fyoung.customerwork.safety.sensitiveword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 敏感词过滤服务：{@code normalize → match}，返回命中与整体决策，支持词表热重建。
 *
 * <p><b>热重建：</b>{@link #reload()} 从 {@link SensitiveWordStore} 拉启用词、归一化后构建<b>不可变</b>
 * {@link AhoCorasickMatcher} 新实例，经 {@code volatile} 引用原子替换——匹配热路径（{@link #check}）只读
 * volatile 引用、<b>不加锁</b>，读到的永远是某个完整快照（旧的或新的），不会读到半构建态。</p>
 *
 * <p><b>fail-closed 词表加载（三分支，安全网关的核心契约）：</b>{@link SensitiveWordStore#findEnabled()}
 * 用 {@code Optional} 区分读成败，{@link #reload()} 据此分流，<b>绝不把"读失败"当成"没词"而静默放行</b>：</p>
 * <ol>
 *   <li><b>读成功</b>（拿到 list，空 list 也算）→ 构建新 matcher 原子替换（"没配词"是合法状态，放行正确）；</li>
 *   <li><b>读失败 + 已有好词表</b> → <b>保留旧 matcher 不替换</b>（一次 DB 抖动不能冲掉已加载的好状态），log.error；</li>
 *   <li><b>读失败 + 从未成功加载过</b> → 装<b>"拦截一切"哨兵</b>（{@code failClosed=true}，非 EMPTY），log.error 告警。
 *       理由：运营显式开了安全网关就是要保护，启动期 DB 挂了宁可"整条对话被拦、立刻暴露去修 DB"，
 *       也绝不能"静默放行、以为在保护"。</li>
 * </ol>
 *
 * <p><b>打码：</b>借 {@link TextNormalizer.Normalized} 的下标映射把命中片段还原到原文区间（含被剔除的插入符），
 * 逐字符替换为掩码字符，保证 {@code 敏*感*词} 也能整段打码、且不改变原文长度（后续命中的下标映射不受影响）。</p>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordFilter.class);

    private static final String CODE_RELOAD_FAIL = "SENSITIVE-RELOAD-FAIL";

    /** fail-closed 哨兵命中所用的合成词（拦截一切时对外呈现的处置元数据）。 */
    private static final SensitiveWord SENTINEL_BLOCK_WORD =
        new SensitiveWord(null, "*", SensitiveWordCategory.CUSTOM, SensitiveWordAction.BLOCK, true);

    private final SensitiveWordStore store;
    private final char maskChar;
    /** 词条动作缺省时的兜底动作（防御式：正常词条均带动作，仅脏数据兜底用）。 */
    private final SensitiveWordAction defaultAction;

    /** 当前生效的自动机快照（volatile：热重建时原子替换，匹配热路径无锁读取）。 */
    private volatile AhoCorasickMatcher matcher;
    /** fail-closed 哨兵态：首次加载即读失败时置 true，check() 对任何非空文本一律 BLOCK。 */
    private volatile boolean failClosed;
    /** 是否曾成功加载过词表（用于区分"读失败保留旧词表"与"首次加载失败 fail-closed"）。 */
    private volatile boolean everLoadedGood;

    public SensitiveWordFilter(SensitiveWordStore store, char maskChar, SensitiveWordAction defaultAction) {
        this.store = store;
        this.maskChar = maskChar;
        this.defaultAction = defaultAction == null ? SensitiveWordAction.BLOCK : defaultAction;
        this.matcher = AhoCorasickMatcher.build(List.of()); // 占位，保证 matcher 非空
        reload();
    }

    /**
     * 从存储重新拉取启用词表并重建自动机（词表变更后调用；线程安全，含 fail-closed 三分支）。
     *
     * @return 是否加载成功——{@code false} 表示读存储失败（分支②/③）。{@link SensitiveWordRefresher}
     *         据此决定是否推进已记录的版本指纹：加载失败就不推进，下一轮继续重试。
     */
    public boolean reload() {
        Optional<List<SensitiveWord>> loaded = store.findEnabled();
        if (loaded.isPresent()) {
            // 分支①：读成功——构建新 matcher 原子替换，解除任何哨兵态
            List<SensitiveWord> enabled = loaded.get();
            List<SensitiveWord> normalized = new ArrayList<>(enabled.size());
            for (SensitiveWord raw : enabled) {
                String normWord = TextNormalizer.normalize(raw.getWord());
                if (!normWord.isEmpty()) {
                    // 只挂归一化匹配词面，原词面保留——命中日志/审计要呈现运营维护的那个词，不是归一化产物
                    normalized.add(raw.withMatchWord(normWord));
                }
            }
            this.matcher = AhoCorasickMatcher.build(normalized);
            this.failClosed = false;
            this.everLoadedGood = true;
            log.info("[SENSITIVE] matcher rebuilt, patterns={}", matcher.patternCount());
            return true;
        }
        if (everLoadedGood) {
            // 分支②：读失败但已有好词表——保留旧 matcher，绝不用空覆盖好状态
            log.error("[SENSITIVE] word table reload failed, keep last good table (patterns={}), code={}",
                matcher.patternCount(), CODE_RELOAD_FAIL);
            return false;
        }
        // 分支③：读失败且从未成功加载——fail-closed 拦截一切，告警促运营修 DB
        this.failClosed = true;
        log.error("[SENSITIVE] initial word table load failed, engage fail-closed block-all sentinel, code={}",
            CODE_RELOAD_FAIL);
        return false;
    }

    /** 当前词表规模（观测 / 单测）。 */
    public int patternCount() {
        return matcher.patternCount();
    }

    /**
     * 流式过滤的最大安全保留长度。保留该旧 API 供外部兼容；项目内流式链路使用
     * {@link #checkStreamWindow(String)} 的动态歧义前缀长度。
     */
    @Deprecated
    public int streamRetainLength() {
        if (failClosed) {
            return 0;
        }
        return Math.max(0, matcher.maxPatternLength() - 1);
    }

    /** 是否处于 fail-closed 哨兵态（观测 / 单测）。 */
    public boolean isFailClosed() {
        return failClosed;
    }

    /**
     * 过滤一段文本：归一化后单趟扫描，返回命中、整体决策与打码文本。
     *
     * @param rawText 原始文本（null / 空返回放行结果）
     */
    public SensitiveWordFilterResult check(String rawText) {
        return check(rawText, matcher, failClosed);
    }

    /**
     * 在同一个词表快照上完成过滤与流式尾部判定，避免热重载恰好发生在两步之间时使用两份词表。
     */
    StreamWindow checkStreamWindow(String rawText) {
        boolean failClosedSnapshot = failClosed;
        AhoCorasickMatcher matcherSnapshot = matcher;
        SensitiveWordFilterResult result = check(rawText, matcherSnapshot, failClosedSnapshot);
        int retainLength = result.decision() == SensitiveWordAction.BLOCK
            ? 0 : streamRetainLength(rawText, matcherSnapshot, failClosedSnapshot);
        return new StreamWindow(result, retainLength);
    }

    private SensitiveWordFilterResult check(String rawText, AhoCorasickMatcher matcherSnapshot,
                                             boolean failClosedSnapshot) {
        if (rawText == null || rawText.isEmpty()) {
            return SensitiveWordFilterResult.pass(rawText);
        }
        if (failClosedSnapshot) {
            // fail-closed 哨兵：词表从未成功加载，任何非空文本一律拦截（合成一个 BLOCK 命中）
            List<SensitiveWordHit> sentinelHits = List.of(new SensitiveWordHit(SENTINEL_BLOCK_WORD, 0, 0));
            return new SensitiveWordFilterResult(rawText, rawText, sentinelHits, SensitiveWordAction.BLOCK);
        }
        TextNormalizer.Normalized norm = TextNormalizer.normalizeTracked(rawText);
        List<SensitiveWordHit> hits = matcherSnapshot.match(norm.text());
        if (hits.isEmpty()) {
            return SensitiveWordFilterResult.pass(rawText);
        }
        SensitiveWordAction decision = highestAction(hits);
        String maskedText = decision == SensitiveWordAction.BLOCK
            ? rawText  // BLOCK 决策整体替换为兜底话术，无需逐词打码
            : maskHits(rawText, norm.originalIndex(), hits);
        return new SensitiveWordFilterResult(rawText, maskedText, hits, decision);
    }

    /** 把归一化歧义前缀映射回原文尾部长度，保留其中用于绕过的空白与插入符。 */
    private int streamRetainLength(String rawText, AhoCorasickMatcher matcherSnapshot,
                                   boolean failClosedSnapshot) {
        if (failClosedSnapshot || rawText == null || rawText.isEmpty()) {
            return 0;
        }
        TextNormalizer.Normalized normalized = TextNormalizer.normalizeTracked(rawText);
        int normalizedRetain = matcherSnapshot.pendingPrefixLength(normalized.text());
        if (normalizedRetain == 0) {
            return 0;
        }
        int normalizedStart = normalized.text().length() - normalizedRetain;
        int rawStart = normalized.originalIndex()[normalizedStart];
        return rawText.length() - rawStart;
    }

    /** 一次流式窗口检查的不可变结果。 */
    record StreamWindow(SensitiveWordFilterResult result, int retainLength) {
    }

    /** 命中词的动作（防御式：动作缺省时回退 {@link #defaultAction}）。 */
    private SensitiveWordAction actionOf(SensitiveWordHit hit) {
        SensitiveWordAction a = hit.word().getAction();
        return a == null ? defaultAction : a;
    }

    /** 取命中中优先级最高的动作（BLOCK &gt; MASK &gt; REVIEW）。 */
    private SensitiveWordAction highestAction(List<SensitiveWordHit> hits) {
        SensitiveWordAction top = null;
        for (SensitiveWordHit hit : hits) {
            SensitiveWordAction a = actionOf(hit);
            if (top == null || a.severity() > top.severity()) {
                top = a;
            }
        }
        return top;
    }

    /** 把 MASK 动作命中片段在原文对应区间（含插入符）逐字符打码；非 MASK 命中不改写。 */
    private String maskHits(String rawText, int[] originalIndex, List<SensitiveWordHit> hits) {
        boolean[] maskFlags = new boolean[rawText.length()];
        boolean any = false;
        for (SensitiveWordHit hit : hits) {
            if (actionOf(hit) != SensitiveWordAction.MASK) {
                continue;
            }
            int origStart = originalIndex[hit.start()];
            int origEnd = originalIndex[hit.end() - 1]; // 含
            for (int i = origStart; i <= origEnd; i++) {
                maskFlags[i] = true;
            }
            any = true;
        }
        if (!any) {
            return rawText;
        }
        char[] chars = rawText.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (maskFlags[i]) {
                chars[i] = maskChar;
            }
        }
        return new String(chars);
    }
}
