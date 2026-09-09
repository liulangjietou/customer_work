package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.List;

/**
 * 默认激活组语义的 Toolkit：把"空 activatedGroups"解释为"未初始化 → 沿用构建期默认激活组"，
 * 而非"停用全部分组工具"。
 *
 * <p>背景（AgentScope 2.0.0 GA 实测行为）：配置了 AgentStateStore 时，{@code ReActAgent} 每次调用
 * 会先用会话槽状态里的 activatedGroups <b>全量覆盖</b> Toolkit 的激活组（先全部停用再按集合激活），
 * 推理时也只按该集合解析工具 schema。新会话的 fresh state 集合为空 → 构建期
 * {@code createToolGroup(active=true)} 声明的业务工具组被整体清空，模型只能看到未分组的基础工具
 * （对话表现为"没有订单查询工具"）。本类通过两处兜底修正该语义：</p>
 * <ul>
 *   <li>{@link #setActiveGroups(List)}：忽略空集合的覆盖，保留默认激活组；</li>
 *   <li>{@link #getToolSchemas(Collection)}：入参为空时回退到按组内置激活标志解析。</li>
 * </ul>
 *
 * <p>会话一旦真实持久化过非空激活组，仍以会话自身集合为准——不影响 meta-tool 运行时动态装备语义。</p>
 * @author owlzhangfq@gmail.com
 */
public class DefaultActiveGroupsToolkit extends ManagedToolkit {

    /** 默认构造：串行 + 安全的工具执行超时。 */
    public DefaultActiveGroupsToolkit() {
        super();
    }

    /** 按部署配置指定执行超时与重试。 */
    public DefaultActiveGroupsToolkit(io.agentscope.core.tool.ToolkitConfig config) {
        super(config);
    }

    /**
     * 返回自身而非防御性拷贝。
     *
     * <p>{@code ReActAgent.Builder#build()} 会调用 {@code toolkit.copy()} 做防御性拷贝——拷贝产物是
     * 基类 {@code Toolkit}，本类的两处守卫覆盖会在拷贝中<b>整体丢失</b>（实测导致修复失效）。本项目中
     * Toolkit 由工厂按会话新建、交给 Builder 后原引用即弃，不存在"同一 Toolkit 喂多个 Agent"的共享
     * 场景，防御性拷贝无隔离价值，故返回自身以保住守卫语义。若未来复用同一实例构建多个 Agent，
     * 需改回拷贝并另寻守卫方案。</p>
     */
    @Override
    public Toolkit copy() {
        return this;
    }

    /** 空集合视为"未初始化"而非"清空"，避免新会话状态把默认激活组抹掉。 */
    @Override
    public void setActiveGroups(List<String> groups) {
        if (CollectionUtils.isEmpty(groups)) {
            return;
        }
        super.setActiveGroups(groups);
    }

    /** 会话侧激活组为空时，回退到构建期默认激活组解析工具面。 */
    @Override
    public List<ToolSchema> getToolSchemas(Collection<String> activeGroups) {
        if (CollectionUtils.isEmpty(activeGroups)) {
            return getToolSchemas();
        }
        return super.getToolSchemas(activeGroups);
    }
}
