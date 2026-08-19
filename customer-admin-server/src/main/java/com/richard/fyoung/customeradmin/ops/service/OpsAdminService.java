package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customerwork.capability.csat.CsatSummary;
import com.richard.fyoung.customerwork.capability.csat.CsatSurvey;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetter;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStatus;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.prompt.PromptVersion;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheEntry;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheScope;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运营闭环后台服务：五个域的读与少量运营动作。
 *
 * <p>刻意只做"读"和"薄薄一层动作"，聚合口径（如 CSAT 的满意率）一律复用 starter 的领域方法
 * （{@link CsatSummary#of}、{@link DeadLetter#reopen}），后台不自己算——两边对同一批数据
 * 给出不同结论时，运营看到的是后台这份，排查时却对着客服端那份，很难发现。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class OpsAdminService {

    private static final Logger log = LoggerFactory.getLogger(OpsAdminService.class);

    /** 从知识盲区一键补知识时的来源标注，便于日后审计"这条知识哪来的"。 */
    private static final String KNOWLEDGE_SOURCE_PREFIX = "knowledge-gap:";

    private final OpsGatewayProvider gatewayProvider;

    public OpsAdminService(OpsGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    // ---------- 语义缓存 ----------

    /** 缓存条目（按命中次数降序）：看清楚缓存了什么、哪些真的在被复用。 */
    public List<SemanticCacheEntry> listCache(String scopeId, int limit) {
        return gatewayProvider.get().semanticCache().listByHits(scopeId, limit);
    }

    /**
     * 列出实际存在的缓存分区（按条目数降序）。
     *
     * <p>缓存分区键是用户级隔离键，运营在看板上猜不到该填什么——这个接口就是为了把
     * "手填一个猜不到的 ID"换成"从实际有数据的分区里选"。跨库门面挂了租户拦截器，
     * 只看得到本租户的分区。</p>
     */
    public List<SemanticCacheScope> listCacheScopes(int limit) {
        return gatewayProvider.get().semanticCache().listScopes(limit);
    }

    /** 定点删除单条缓存（某条答得不对时不必清空整个分区）。 */
    public boolean evictCacheEntry(Long id) {
        boolean removed = gatewayProvider.get().semanticCache().remove(id);
        log.info("semantic cache entry evicted by admin: id={}, removed={}", id, removed);
        return removed;
    }

    /** 清空分区缓存：知识库或提示词改过之后，旧答案不再可信。 */
    public int clearCache(String scopeId) {
        int removed = gatewayProvider.get().semanticCache().clear(scopeId);
        log.info("semantic cache cleared by admin: scopeId={}, removed={}", scopeId, removed);
        return removed;
    }

    // ---------- 提示词版本 ----------

    /** 版本历史（观测时间倒序）。 */
    public List<PromptVersion> listPromptVersions(int limit) {
        return gatewayProvider.get().promptVersion().findRecent(limit);
    }

    /** 按指纹取全文（归因时比对两版差异）。 */
    public PromptVersion getPromptVersion(String fingerprint) {
        return gatewayProvider.get().promptVersion().find(fingerprint)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "提示词版本不存在：" + fingerprint));
    }

    // ---------- CSAT ----------

    /**
     * 满意度汇总。
     *
     * <p>口径复用 {@link CsatSummary#of}：满意按 4 分及以上算（行业标准），不是平均分——
     * 平均分会被大量 3 分拉成一个看着还行的数字，掩盖真正不满的那批人。</p>
     */
    public CsatSummary csatSummary(String scopeId, long startMs, long endMs) {
        List<CsatSurvey> surveys = gatewayProvider.get().csat().findByWindow(scopeId, startMs, endMs);
        return CsatSummary.of(surveys);
    }

    /** 窗口内的原始调查记录（看低分留言）。 */
    public List<CsatSurvey> csatSurveys(String scopeId, long startMs, long endMs) {
        return gatewayProvider.get().csat().findByWindow(scopeId, startMs, endMs);
    }

    // ---------- 知识盲区 ----------

    /** 盲区排行：反复查不到的问题，越靠前越该优先补。 */
    public List<KnowledgeGap> topKnowledgeGaps(String scopeId, int limit) {
        return gatewayProvider.get().knowledgeGap().topGaps(scopeId, limit);
    }

    /**
     * 从盲区一键补知识：直接往客服端库的 FAQ 表插一条。
     *
     * <p>标题正文由运营填而非拿盲区原问题照抄——用户的提问是口语化的，直接入库会污染检索质量。</p>
     *
     * @return 新建的知识条目 ID
     */
    public Long fillKnowledgeGap(String title, String content, String keyword, String questionHash) {
        KnowledgeMapper mapper = gatewayProvider.get().knowledgeMapper();
        KnowledgeDO entry = new KnowledgeDO();
        entry.setTitle(title);
        entry.setContent(content);
        entry.setKeyword(keyword);
        entry.setSource(KNOWLEDGE_SOURCE_PREFIX + questionHash);
        mapper.insert(entry);
        log.info("knowledge gap filled: knowledgeId={}, questionHash={}", entry.getId(), questionHash);
        return entry.getId();
    }

    // ---------- 死信队列 ----------

    /** 按状态列出死信（待重投 / 已放弃）。 */
    public List<DeadLetter> listDeadLetters(DeadLetterStatus status, int limit) {
        return gatewayProvider.get().deadLetter().findByStatus(status, limit);
    }

    /** 各状态计数（角标）。 */
    public long countDeadLetters(DeadLetterStatus status) {
        return gatewayProvider.get().deadLetter().count(status);
    }

    /**
     * 人工重开一条已放弃的死信（运营确认下游恢复后触发）。
     *
     * <p>重开会清零重试次数——{@link DeadLetter#reopen} 里的逻辑，否则刚放回去就又立刻耗尽。
     * 真正的重投由客服端的巡检器执行，后台只是把它放回队列。</p>
     */
    public DeadLetter reopenDeadLetter(String id) {
        DeadLetter letter = gatewayProvider.get().deadLetter().find(id)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "死信不存在：" + id));
        letter.reopen(System.currentTimeMillis());
        gatewayProvider.get().deadLetter().save(letter);
        log.info("dead letter reopened by admin: id={}, type={}", id, letter.getType());
        return letter;
    }
}
