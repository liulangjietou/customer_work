package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.properties.KnowledgeGapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识盲区分析：告诉运营该补哪些知识。
 *
 * <p>此前知识库有检索、有防护、有测试接口，唯独没人统计"哪些问题反复查不到"——
 * 于是补知识全靠拍脑袋，而拍出来的往往是运营自己关心的，不是用户实际在问的。
 * 这份数据本来只需在检索未命中时记一笔。</p>
 *
 * <p><b>记录必须极轻</b>：它挂在每一次知识检索的尾巴上，稍重一点就会拖慢主链路。
 * 因此只做一次 upsert 计数，且全程吞异常——统计失败最坏是少一条排行数据，
 * 绝不该让用户的问题因此答不出来。</p>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeGapService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapService.class);

    private final KnowledgeGapStore store;
    private final TenantResolver tenantResolver;
    private final KnowledgeGapProperties properties;

    public KnowledgeGapService(KnowledgeGapStore store, TenantResolver tenantResolver,
                               KnowledgeGapProperties properties) {
        this.store = store;
        this.tenantResolver = tenantResolver;
        this.properties = properties;
    }

    /**
     * 记一次检索未命中（旁路，永不抛出）。
     *
     * @param sessionId 会话 ID，用于解析分区；可为空
     * @param question  用户的原始问题
     */
    public void recordMiss(String sessionId, String question) {
        if (!properties.isEnabled() || !StringUtils.hasText(question)) {
            return;
        }
        int length = question.trim().length();
        // 太短的（"嗯""在吗"）本就不该指望知识库命中，计进去只会淹没真正的盲区
        if (length < properties.getMinQuestionLength()) {
            return;
        }
        try {
            store.recordMiss(question, tenantResolver.resolve(sessionId), System.currentTimeMillis());
        } catch (Exception e) {
            log.error("record knowledge gap failed, errorCode={}, sessionId={}",
                "KNOWLEDGE-GAP-RECORD-FAIL", sessionId, e);
        }
    }

    /** 盲区排行：未命中次数最多的若干条，即"最该优先补的知识"。 */
    public List<KnowledgeGap> topGaps(String scopeId, int limit) {
        String scope = StringUtils.hasText(scopeId) ? scopeId : TenantResolver.DEFAULT_TENANT;
        return store.topGaps(scope, limit);
    }
}
