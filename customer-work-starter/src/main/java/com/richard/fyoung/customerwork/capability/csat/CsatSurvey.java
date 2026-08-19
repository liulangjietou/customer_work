package com.richard.fyoung.customerwork.capability.csat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话级满意度调查（CSAT）。
 *
 * <p>与消息级点赞/点踩（{@code MessageFeedback}）是两个不同的指标，不能互相替代：
 * 点踩衡量的是<b>某一句答得好不好</b>，CSAT 衡量的是<b>这次服务整体解决了没有</b>。
 * 一次会话可能每句话都答得挺像样，但问题始终没解决——那会拿到一堆 UP 和一个 2 分。
 * CSAT 是客服行业最标准的运营指标，此前系统完全拿不到。</p>
 *
 * <p>以 sessionId 为自然主键：一次会话只该有一次整体评价，重复提交按最新一次覆盖
 * （用户改主意允许更正）。</p>
 *
 * @param sessionId     会话 ID
 * @param scopeId       运营统计分区键（OpsScopeResolver 取当前租户，无上下文回落 default）
 * @param score         评分 1-5；{@code null} 表示已邀请但用户还没评
 * @param comment       可选的文字说明
 * @param invitedAtMs   发出邀请的时间戳（毫秒）
 * @param submittedAtMs 提交评分的时间戳（毫秒）；未评价为 0
 * @author owlzhangfq@gmail.com
 */
public record CsatSurvey(
    String sessionId,
    String scopeId,
    Integer score,
    String comment,
    long invitedAtMs,
    long submittedAtMs
) {

    /** 评分下界。 */
    public static final int MIN_SCORE = 1;

    /** 评分上界。 */
    public static final int MAX_SCORE = 5;

    /**
     * 满意的最低分。
     *
     * <p>CSAT 的行业口径是"4 分及以上算满意"，而不是平均分——平均分会被大量 3 分（无感）拉成一个
     * 看着还行的数字，掩盖掉真正不满的那批人。</p>
     */
    public static final int SATISFIED_THRESHOLD = 4;

    /** 发出邀请：此时还没有评分。 */
    public static CsatSurvey invited(String sessionId, String scopeId, long nowMs) {
        return new CsatSurvey(sessionId, scopeId, null, null, nowMs, 0L);
    }

    /**
     * 提交评分，返回新的快照。
     *
     * @throws IllegalArgumentException 评分越界时——越界分数会污染 CSAT 统计，必须挡在入口
     */
    public CsatSurvey withScore(int newScore, String newComment, long nowMs) {
        if (newScore < MIN_SCORE || newScore > MAX_SCORE) {
            throw new IllegalArgumentException(
                "csat score must be within [" + MIN_SCORE + ", " + MAX_SCORE + "], got " + newScore);
        }
        return new CsatSurvey(sessionId, scopeId, newScore, newComment, invitedAtMs, nowMs);
    }

    /**
     * 用户是否已评价。
     *
     * <p>与 {@link #satisfied()} 都必须标 {@code @JsonProperty}：Jackson 序列化 record 只认组件，
     * 普通方法不进 JSON。漏标时前端按 {@code answered} 过滤会全部落空——列表永远显示"暂无数据"，
     * 而接口其实返回得好好的，这种"没报错但也没数据"最难查。</p>
     */
    @JsonProperty("answered")
    public boolean answered() {
        return score != null;
    }

    /** 是否算作"满意"（4 分及以上）。 */
    @JsonProperty("satisfied")
    public boolean satisfied() {
        return score != null && score >= SATISFIED_THRESHOLD;
    }
}
