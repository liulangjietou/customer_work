package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 事实日志配置（三层记忆第三层）。
 *
 * <p>只有落库一种形态（{@code cw_fact_log}），故没有目录 / 轮转配置——事实日志是合规审计与
 * 数据飞轮的底稿，落在单机磁盘上多副本各看各的、容器销毁即丢，那种形态不该继续提供。</p>
 */
@Data
public class FactLogProperties {
    /** 是否启用只追加事实日志（可审计、跨会话）。 */
    private boolean enabled = true;
    /**
     * 单次读取的条数上限：事实日志只增不减，不封顶会把整表拉进内存。超限时保留最近 N 条。
     */
    private int readLimit = 10000;
}
