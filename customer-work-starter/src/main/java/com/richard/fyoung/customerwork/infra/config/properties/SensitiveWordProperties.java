package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词"一次拦截"过滤配置。默认关闭。
 *
 * <p>入站（用户输入）与出站（AI 输出）各过一遍高性能敏感词自动机，命中即处置（BLOCK/MASK/REVIEW）。
 * 零 LLM、微秒级、可解释。词表由 {@code store-mode} 决定持久化方式（memory 带演示种子 / jdbc 跨实例共享）。</p>
 */
@Data
public class SensitiveWordProperties {
    /** 命中 BLOCK 时的掩码字符默认值。 */
    public static final String DEFAULT_MASK_CHAR = "*";
    /** 入站命中 BLOCK 的统一安全话术。 */
    public static final String DEFAULT_INBOUND_SAFE_REPLY = "您的消息包含不当内容，请调整后重试。";
    /** 出站命中 BLOCK 的安全兜底话术（替换 AI 原始回复）。 */
    public static final String DEFAULT_OUTBOUND_SAFE_REPLY = "抱歉，这个问题我暂时无法回答，请换个方式提问或联系人工客服。";

    /** 总开关。默认关闭。 */
    private boolean enabled = false;
    /** 生效方向：inbound（仅入站）| outbound（仅出站）| both（默认）。 */
    private Direction direction = Direction.BOTH;
    /** 存储模式：memory（进程内，带演示种子，默认）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
    /** 词条动作缺省时的兜底动作（防御式：正常词条均带动作，仅脏数据/异常兜底用），默认 BLOCK。 */
    private com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction defaultAction =
        com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction.BLOCK;
    /** 打码字符（取首字符）。 */
    private String maskChar = DEFAULT_MASK_CHAR;
    /** 入站 BLOCK 安全话术。 */
    private String inboundSafeReply = DEFAULT_INBOUND_SAFE_REPLY;
    /** 出站 BLOCK 安全兜底话术。 */
    private String outboundSafeReply = DEFAULT_OUTBOUND_SAFE_REPLY;
    /** 是否开启词表定时刷新（后台改词后自动生效）。默认开启。 */
    private boolean refreshEnabled = true;
    /** 词表刷新轮询间隔（毫秒，默认 60s）；每轮只查一次版本指纹，指纹变了才重建自动机。 */
    private long refreshIntervalMs = 60_000L;
    /** 命中日志落库配置（供后台"命中看板"查询）。 */
    private final HitLog hitLog = new HitLog();

    /**
     * 敏感词命中日志落库配置。
     *
     * <p>默认关闭：命中日志会记录用户原文片段，属于敏感数据，是否留存由使用方按合规要求显式开启。
     * 开启后命中记录经有界队列异步落 {@code cw_sensitive_word_hit_log}，不阻塞对话主链路。</p>
     */
    @Data
    public static class HitLog {
        /** 是否记录命中日志。默认关闭。 */
        private boolean enabled = false;
        /** 存储模式：memory（进程内环形缓冲，默认）| jdbc（落库，后台看板用）。 */
        private String storeMode = "memory";
        /** 异步落库队列容量；队列满时丢弃新记录并计数，绝不阻塞对话链路。 */
        private int queueCapacity = 2048;
        /** 留存的原文片段最大长度（字符），超出截断，避免整段用户输入落库。 */
        private int snippetMaxLength = 64;
    }

    /** 取打码字符（配置为空则回退默认 '*'）。 */
    public char resolveMaskChar() {
        return (maskChar == null || maskChar.isEmpty()) ? DEFAULT_MASK_CHAR.charAt(0) : maskChar.charAt(0);
    }

    public boolean inboundEnabled() {
        return enabled && direction != Direction.OUTBOUND;
    }

    public boolean outboundEnabled() {
        return enabled && direction != Direction.INBOUND;
    }
}
