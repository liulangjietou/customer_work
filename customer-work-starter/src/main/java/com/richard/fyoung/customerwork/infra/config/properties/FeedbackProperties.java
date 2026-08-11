package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈存储配置。
 *
 * <p>决定 {@code FeedbackService} 的反馈持久化方式：memory 模式下多实例部署时，反馈写在实例 A、
 * 查询落到实例 B 会查不到；jdbc 模式跨实例共享同一份反馈数据。</p>
 */
@Data
public class FeedbackProperties {
    /** 存储模式：memory（进程内，默认，仅单实例场景适用）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
}
