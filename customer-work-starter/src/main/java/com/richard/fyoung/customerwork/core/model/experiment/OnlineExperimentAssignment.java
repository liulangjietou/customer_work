package com.richard.fyoung.customerwork.core.model.experiment;

/**
 * 单次模型调用真实命中的在线实验曝光事实。
 *
 * <p>这里只记录不可识别的分桶结果与部署身份，不记录用户、会话或分桶盐值。</p>
 */
public record OnlineExperimentAssignment(
    Long experimentId,
    Integer revision,
    String arm,
    Long deploymentId,
    Integer bucket
) {
}
