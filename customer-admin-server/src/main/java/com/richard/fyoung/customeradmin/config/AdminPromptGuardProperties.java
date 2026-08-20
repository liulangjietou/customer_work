package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台链路的<b>直接</b>提示词注入防护参数：{@code admin.prompt-guard.*}。
 *
 * <p>与 {@link AdminInjectionGuardProperties} 的分工：那个管<b>间接</b>注入
 * （工具/MCP 返回体里夹带的指令），这个管<b>直接</b>注入（用户自己在输入框里打的
 * "忽略以上所有指令"）。两条入口的载荷来源不同，但话术本质相同，因此默认词表共用
 * {@link HooksProperties.PromptGuard#DEFAULT_INJECTION_PATTERNS}。</p>
 *
 * <p>此前后台链路两条都缺——间接注入那条在批次中补上了，直接注入这条一直没有实现也没有配置项。</p>
 *
 * <p>默认关闭，与客服端 {@code customer-work.hooks.prompt-guard.enabled} 的缺省一致：
 * 拦截是按正则命中直接拒答，误伤代价由业务承担，开不开由使用方决定。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
@ConfigurationProperties(prefix = "admin.prompt-guard")
public class AdminPromptGuardProperties extends HooksProperties.PromptGuard {
}
