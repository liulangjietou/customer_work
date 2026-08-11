package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 事实日志配置（三层记忆第三层）。 */
@Data
public class FactLogProperties {
    /** 是否启用只追加事实日志（可审计、跨会话）。 */
    private boolean enabled = true;
    private String directory = "./data/facts";
    /** 单文件最大大小（MB），超过则轮转到 .1 / .2 归档；<=0 禁用轮转。 */
    private int maxFileMb = 10;
    /** 最多保留的归档文件数（超出最旧的自动删除）；<=0 不限制。 */
    private int maxArchivedFiles = 5;
}
