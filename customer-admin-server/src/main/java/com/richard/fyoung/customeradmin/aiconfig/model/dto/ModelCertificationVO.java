package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型部署认证视图。
 *
 * <p><b>两个状态字段语义不同，别混用</b>——前端曾拿 {@code effectiveStatus} 判「这次认证跑得怎么样」，
 * 导致存量豁免部署每一次成功的认证都被报成失败，而检查项列表里一条失败都没有：</p>
 * <ul>
 *   <li>{@code status}：<b>这次运行</b>的结论，只有 PASSED / FAILED。问「这次跑得怎么样」看它，
 *       历史列表同理——每条历史行要回答的是当时那次的结论；</li>
 *   <li>{@code effectiveStatus}：<b>该部署当前的门禁态</b>，在运行结论之上叠加了有效期、端点 revision
 *       与凭据版本漂移，取值还包括 STALE / EXPIRED / UNKNOWN / NOT_REQUIRED。问「现在能不能激活、
 *       能不能被路由引用」看它。存量豁免部署（{@code certification_required=0}）恒为 NOT_REQUIRED，
 *       与本次跑得如何无关。</li>
 * </ul>
 */
@Data
public class ModelCertificationVO {
    private Long runId;

    /** 本次运行结论：PASSED / FAILED。判断「这次认证成功没有」用它。 */
    private String status;

    /** 该部署当前门禁态：PASSED / FAILED / STALE / EXPIRED / UNKNOWN / NOT_REQUIRED。判断「能否激活」用它。 */
    private String effectiveStatus;

    private String staleReason;
    private Integer certifiedEndpointRevision;
    private Integer certifiedSecretVersion;
    private LocalDateTime validUntil;
    private LocalDateTime completedAt;
    private Integer passedChecks;
    private Integer failedChecks;
    private Long latencyP95Ms;
    private Integer verifiedContextTokens;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private String currency;
    private String failureCode;
    private String failureMessage;
    private List<ModelCertificationCheckVO> checks;
}
