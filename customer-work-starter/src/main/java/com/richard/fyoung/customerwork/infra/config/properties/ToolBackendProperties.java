package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务工具后端配置。
 *
 * <p>{@code mode} 决定订单/商品/售后/会员/投诉/知识库六个后端的实现：{@code mock}（进程内示例，默认）|
 * {@code jdbc}（MyBatis-Plus 实现，落 {@code cw_order} 等演示表）。jdbc 模式与各域 store-mode=jdbc 一样，
 * 触发 starter 独立持久化环境（{@code CustomerWorkPersistenceConfig}）装配。</p>
 */
@Data
public class ToolBackendProperties {
    /** 存储模式：mock（进程内示例，默认）| jdbc（MyBatis-Plus 实现）。 */
    private String mode = "mock";
}
