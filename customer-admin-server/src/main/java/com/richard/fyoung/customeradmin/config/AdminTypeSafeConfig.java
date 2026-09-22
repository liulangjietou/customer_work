package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.TypeSafeSystemOneClient;
import com.richard.fyoung.customerwork.core.middleware.JevEscalationMiddleware;
import com.richard.fyoung.customerwork.core.middleware.JevRefundRiskMiddleware;
import com.richard.fyoung.customerwork.core.middleware.JevToolScopeMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 后台智能体的 Jev 接入：三个决策点跑影子模式（只判定、只展示，不执行任何动作也不改模型输入），
 * 答复安全闸门真执行并展示。
 *
 * <h3>为什么读的是 {@code customer-work.typesafe.*} 而不是另起一组 {@code admin.typesafe.*}</h3>
 * <p>影子模式的全部价值在于「后台展示的判定就是线上会做的判定」。后台若有自己的一套阈值，
 * 运维改了其中一边，后台显示的就不再是线上真实会做的决策，而两边都不会报错。
 * 读同一组键，两个进程共用同一份配置（如 Nacos 公共 dataId 或同一组环境变量）即可保持一致。</p>
 *
 * <h3>为什么用 Binder 绑到一个不注册成 Bean 的实例上</h3>
 * <p>后台排除了 starter 自动装配，容器里已有一个手工构建的 {@link CustomerWorkProperties}
 * （内容风控用，见 {@code AdminSensitiveWordFilterConfig}），它并不绑定 {@code customer-work.*}。
 * 再注册一个同类型 Bean 会让现有的按类型注入产生歧义；按键绑到本地实例则两边互不影响。</p>
 *
 * <p>后台没有 {@link HandoffService}（转人工属于客服端），答复闸门命中时只拦截并追加澄清、不转人工。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AdminTypeSafeConfig {

    private static final String TYPESAFE_PREFIX = "customer-work.typesafe";
    private static final String HOOKS_PREFIX = "customer-work.hooks";

    /** 与客服端同一个开关：关着时容器里没有它，各中间件原样透传。 */
    @Bean
    @ConditionalOnProperty(prefix = TYPESAFE_PREFIX, name = "enabled", havingValue = "true")
    public JevDecisionService adminJevDecisionService(Environment environment,
                                                      ObjectProvider<MeterRegistry> meterRegistryProvider) {
        TypeSafeProperties properties = new TypeSafeProperties();
        Binder.get(environment).bind(TYPESAFE_PREFIX, Bindable.ofInstance(properties));
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        return new JevDecisionService(new TypeSafeSystemOneClient(properties, meterRegistry), properties,
            meterRegistry);
    }

    @Bean
    public AdminJevMiddlewares adminJevMiddlewares(Environment environment,
                                                   ObjectProvider<JevDecisionService> jevProvider,
                                                   ObjectProvider<HandoffService> handoffProvider,
                                                   ObjectProvider<AuditSink> auditSinkProvider,
                                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        CustomerWorkProperties runtime = new CustomerWorkProperties();
        Binder.get(environment).bind(HOOKS_PREFIX, Bindable.ofInstance(runtime.getHooks()));
        return new AdminJevMiddlewares(
            new JevEscalationMiddleware(jevProvider, handoffProvider, JevRunMode.SHADOW),
            new JevToolScopeMiddleware(jevProvider, JevRunMode.SHADOW),
            new JevRefundRiskMiddleware(jevProvider, handoffProvider, JevRunMode.SHADOW),
            new SelfCorrectionMiddleware(runtime, handoffProvider, auditSinkProvider, meterRegistryProvider,
                jevProvider::getIfAvailable, JevRunMode.LIVE_TRACED));
    }
}
