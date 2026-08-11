package com.richard.fyoung.customerwork.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Collections;
import java.util.List;

/**
 * 域装配的入口联动条件:下游把入口 {@link CustomerWorkAutoConfiguration} 加进
 * {@code spring.autoconfigure.exclude} 时,全部域装配一并让位(admin-server / customer-channel
 * 的既有 exclude 配置无需任何调整)。
 *
 * <p>为什么不用标记 Bean({@code @ConditionalOnBean}):域装配类带 {@code @ComponentScan},
 * 而配置类解析期会用 REGISTER_BEAN 阶段条件预评估扫描门控——那一刻入口类的 @Bean 定义尚未注册,
 * 扫描会被静默跳过(类本身却在注册期通过条件),形成"装配类生效但域内组件全部缺席"的假象。
 * 本条件直接读 Environment,解析期即可稳定评估,与扫描时序一致。</p>
 *
 * <p>注意:仅识别属性形式的 exclude;用 {@code @SpringBootApplication(exclude = ...)} 注解排除
 * 入口类不会联动域装配,此时请改用属性形式或按域开关关闭。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class OnCustomerWorkEntryCondition extends SpringBootCondition {

    static final String ENTRY_CLASS =
        "com.richard.fyoung.customerwork.autoconfigure.CustomerWorkAutoConfiguration";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        List<String> excludes = Binder.get(context.getEnvironment())
            .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
            .orElse(Collections.emptyList());
        ConditionMessage.Builder message = ConditionMessage.forCondition("CustomerWork entry");
        if (excludes.contains(ENTRY_CLASS)) {
            return ConditionOutcome.noMatch(
                message.because("entry auto-configuration excluded via spring.autoconfigure.exclude"));
        }
        return ConditionOutcome.match(message.because("entry auto-configuration not excluded"));
    }
}
