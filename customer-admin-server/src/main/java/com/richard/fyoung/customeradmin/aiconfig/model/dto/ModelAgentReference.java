package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

/**
 * 模型配置的智能体引用投影。
 *
 * <p>同时携带引用方租户，是因为 {@code default} 共享模型可能被多个业务租户的智能体引用；
 * 缓存失效和可靠发布都必须切回智能体自己的租户上下文执行。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class ModelAgentReference {

    private String tenantId;
    private Long agentId;
    private String agentCode;
}
