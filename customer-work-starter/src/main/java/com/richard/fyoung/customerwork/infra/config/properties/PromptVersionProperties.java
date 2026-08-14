package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 提示词版本追踪配置。
 *
 * <p>版本历史是效果归因的底座："指标掉了是不是提示词改的"这个问题，
 * 只有在能查到历史版本时才回答得了。memory 模式重启即丢，生产切 jdbc。</p>
 */
@Data
public class PromptVersionProperties {

    /** 存储模式：memory（进程内，默认）| jdbc（落 cw_prompt_version）。 */
    private String storeMode = "memory";
}
