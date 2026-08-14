package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * badcase 回流配置。
 *
 * <p>badcase 的价值在于"攒够一批再集中筛"，memory 模式每次重启都会把待筛队列清零，
 * 生产必须切 jdbc。</p>
 */
@Data
public class BadcaseProperties {

    /** 存储模式：memory（进程内，默认）| jdbc（落 cw_badcase，跨实例共享且重启不丢）。 */
    private String storeMode = "memory";

    /**
     * 是否把负反馈与质检失败自动登记为 badcase（默认开）。
     *
     * <p>关掉它不会影响事实流水的记录（那是审计，永远要留），只是不再进人工筛选队列——
     * 适用于反馈量极大、打算用离线批处理另做筛选的场景。</p>
     */
    private boolean autoCollect = true;
}
