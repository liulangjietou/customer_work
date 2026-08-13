package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/** 事实日志配置（三层记忆第三层）。 */
@Data
public class FactLogProperties {
    /** 是否启用只追加事实日志（可审计、跨会话）。 */
    private boolean enabled = true;
    /**
     * 存储后端：jdbc（默认，落 cw_fact_log 表）| file（按分区落盘 JSONL）。
     *
     * <p>默认 jdbc：事实日志是合规审计与数据飞轮的底稿，落在单机磁盘上多副本各看各的、
     * 容器销毁即丢。持久化环境不可用时自动降级落盘（见 {@code FactLogConfig}），不阻断启动。</p>
     */
    private String storeMode = "jdbc";
    /** 落盘目录（store-mode=file 时生效）。 */
    private String directory = "./data/facts";
    /** 单文件最大大小（MB），超过则轮转到 .1 / .2 归档；<=0 禁用轮转（store-mode=file 时生效）。 */
    private int maxFileMb = 10;
    /** 最多保留的归档文件数（超出最旧的自动删除）；<=0 不限制（store-mode=file 时生效）。 */
    private int maxArchivedFiles = 5;
    /**
     * 单次读取的条数上限（store-mode=jdbc 时生效）：事实日志只增不减，
     * 不封顶会把整表拉进内存。超限时保留最近 N 条。
     */
    private int readLimit = 10000;
}
