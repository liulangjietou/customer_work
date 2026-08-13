package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 会话级满意度调查服务。
 *
 * <p>此前系统只有消息级的点赞/点踩，拿不到"这次服务整体解决了没有"——而后者才是客服行业
 * 最标准的运营指标。两者不能互相替代：每句话都答得像样但问题始终没解决的会话，
 * 会拿到一堆 UP 和一个 2 分。</p>
 *
 * <p><b>邀请与评分分开记</b>：只记评分就算不出回收率，而回收率低的时候，那个漂亮的 CSAT 分数
 * 其实只代表愿意评价的一小撮人——特别满意和特别不满的两头，中间的沉默大多数不在样本里。
 * 没有回收率，CSAT 就是个会骗人的数字。</p>
 * @author owlzhangfq@gmail.com
 */
public class CsatService {

    private static final Logger log = LoggerFactory.getLogger(CsatService.class);

    private final CsatStore store;
    private final TenantResolver tenantResolver;

    public CsatService(CsatStore store, TenantResolver tenantResolver) {
        this.store = store;
        this.tenantResolver = tenantResolver;
    }

    /**
     * 发出满意度邀请（幂等）。
     *
     * <p>已邀请过的会话直接返回原记录，不重置邀请时间也不清空已有评分——
     * 会话可能被多次结束（超时清理、用户主动关闭），重复邀请会把回收率的分母灌水。</p>
     */
    public CsatSurvey invite(String sessionId) {
        Optional<CsatSurvey> existing = store.find(sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        CsatSurvey survey = CsatSurvey.invited(sessionId, tenantResolver.resolve(sessionId),
            System.currentTimeMillis());
        store.save(survey);
        log.info("csat invited: sessionId={}", sessionId);
        return survey;
    }

    /**
     * 提交评分。未邀请过的会话会先补一条邀请记录——用户主动评价是好事，不该因为"没请你评"而拒收。
     *
     * @throws IllegalArgumentException 评分越界时
     */
    public CsatSurvey submit(String sessionId, int score, String comment) {
        CsatSurvey survey = store.find(sessionId)
            .orElseGet(() -> CsatSurvey.invited(sessionId, tenantResolver.resolve(sessionId),
                System.currentTimeMillis()));
        CsatSurvey answered = survey.withScore(score, comment, System.currentTimeMillis());
        store.save(answered);
        log.info("csat submitted: sessionId={}, score={}, satisfied={}",
            sessionId, score, answered.satisfied());
        return answered;
    }

    /** 查某次会话的调查状态（用户端据此决定要不要弹评分卡）。 */
    public Optional<CsatSurvey> find(String sessionId) {
        return store.find(sessionId);
    }

    /**
     * 按分区与时间窗汇总。
     *
     * @param scopeId 分区键；空白则按 {@code default}
     */
    public CsatSummary summary(String scopeId, long startMs, long endMs) {
        String scope = StringUtils.hasText(scopeId) ? scopeId : TenantResolver.DEFAULT_TENANT;
        List<CsatSurvey> surveys = store.findByWindow(scope, startMs, endMs);
        return CsatSummary.of(surveys);
    }
}
