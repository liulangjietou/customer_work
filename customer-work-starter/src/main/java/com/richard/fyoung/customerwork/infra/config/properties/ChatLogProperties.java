package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天日志存储配置。
 *
 * <p>{@code store-mode} 决定 {@code ChatLogService} 的消息留痕持久化方式（memory 仅单实例 / jdbc 跨实例）。</p>
 */
@Data
public class ChatLogProperties {
    /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
}
