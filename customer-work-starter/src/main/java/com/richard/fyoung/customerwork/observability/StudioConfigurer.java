package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.studio.StudioManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Studio 可视化调试装配（对应「可观测 · Studio」）。
 *
 * <p>启用后初始化 {@link StudioManager} 并连接 AgentScope Studio，把运行轨迹推送到 Studio 做
 * 可视化调试与回放。需要外部 Studio 服务，默认关闭；连接为异步（{@code initialize()} 返回 Mono），
 * 失败不影响应用启动。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class StudioConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StudioConfigurer.class);

    private final CustomerWorkProperties properties;

    public StudioConfigurer(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        CustomerWorkProperties.Observability.Studio s = properties.getObservability().getStudio();
        return s.isEnabled() && StringUtils.hasText(s.getUrl());
    }

    @PostConstruct
    public void init() {
        if (!isEnabled()) {
            return;
        }
        CustomerWorkProperties.Observability.Studio s = properties.getObservability().getStudio();
        try {
            StudioManager.init()
                .studioUrl(s.getUrl())
                .project(s.getProject())
                .runName(s.getRunName())
                .initialize()
                .subscribe(
                    v -> log.info("[Studio] 已连接 {}", s.getUrl()),
                    e -> log.warn("[Studio] 连接失败（忽略）: {}", e.getMessage()));
        } catch (Exception e) {
            log.warn("[Studio] 初始化失败（忽略）: {}", e.getMessage());
        }
    }
}
