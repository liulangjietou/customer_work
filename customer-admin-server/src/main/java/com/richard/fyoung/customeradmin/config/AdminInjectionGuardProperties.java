package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 间接提示词注入防护参数：{@code admin.injection-guard.*}。
 *
 * <p><b>为什么后台侧默认开启，而客服端（{@code customer-work.hooks.indirect-injection-guard}）默认关闭</b>：
 * 两边的攻击面不是一个量级。后台智能体的工具来源是<b>管理员在页面上任意配置的 MCP 服务</b>，
 * 返回体完全不受本系统控制；知识库同理，绑的是外部 RAG 服务的库。而客服端的工具是代码里写死的
 * 几个业务后端。再加上隔离标记本身是确定性的（不判断内容善恶、不误杀、不加延迟、不额外调模型），
 * 默认开的代价接近零，默认关的代价是安全能力形同虚设。</p>
 *
 * <p>注意本配置只管<b>工具结果</b>那一侧。知识库召回内容的隔离直接内建在
 * {@code KnowledgeRetrievalMiddleware} 里恒生效，不设开关——召回内容本来就是这个中间件自己产出的，
 * 包一层是它的份内事，多一个开关只会多一种"开了 RAG 却关了隔离"的危险配置组合。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.injection-guard")
public class AdminInjectionGuardProperties {

    /** 工具/MCP 结果隔离标记总开关。 */
    private boolean enabled = true;

    /** 是否对工具结果跑注入检测（命中只记 error 日志与指标，不拦截、不改写内容）。 */
    private boolean detectionEnabled = true;

    /**
     * 检测正则列表（不区分大小写）。默认复用 starter 的
     * {@link CustomerWorkProperties.Hooks.PromptGuard#DEFAULT_INJECTION_PATTERNS}——
     * 直接注入与间接注入的攻击话术本质相同，差别只在载荷从哪条路进来，没有必要维护两套词表。
     */
    private List<String> injectionPatterns =
        new ArrayList<>(CustomerWorkProperties.Hooks.PromptGuard.DEFAULT_INJECTION_PATTERNS);
}
