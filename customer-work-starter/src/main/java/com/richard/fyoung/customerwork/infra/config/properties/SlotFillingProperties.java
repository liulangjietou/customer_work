package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮槽位收集存储配置。
 *
 * <p>决定 {@code SlotFillingService} 收集进度的持久化方式，语义与 {@link HumanApproval#storeMode}
 * 一致：memory 模式下应用重启会丢失正在进行的多轮收集（用户需重新开始）；jdbc 模式持久化到数据库，
 * 重启可续填。</p>
 */
@Data
public class SlotFillingProperties {
    /** 存储模式：memory（进程内，默认）| jdbc（数据库持久化）。 */
    private String storeMode = "memory";
}
