package com.richard.fyoung.customeradmin.configversion.entity;

/**
 * 配置类型：决定快照内容的组装方式与发布目标。
 * @author owlzhangfq@gmail.com
 */
public enum ConfigType {

    /** 智能体运行时配置（含系统提示词、模型绑定、工具集），下发到客服端。 */
    AGENT,

    /** 模型配置。 */
    MODEL;

    public static ConfigType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AGENT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}
