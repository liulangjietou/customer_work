package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 会话级满意度（CSAT）配置。
 *
 * <p>CSAT 是要按周按月看趋势的运营指标，memory 模式重启即清空，趋势无从谈起，生产必须切 jdbc。</p>
 */
@Data
public class CsatProperties {

    /** 存储模式：memory（进程内，默认）| jdbc（落 cw_csat_survey）。 */
    private String storeMode = "memory";

    /**
     * 会话结束时是否自动发出满意度邀请（默认开）。
     *
     * <p>关掉它并不影响用户主动提交评分——{@code CsatService#submit} 会补建记录。
     * 只是那样算出的回收率没有意义（分母不完整）。</p>
     */
    private boolean inviteOnSessionEnd = true;
}
