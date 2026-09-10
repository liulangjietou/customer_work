package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具面配置：这个部署要向模型暴露哪些业务工具组。
 *
 * <h3>为什么需要它</h3>
 * <p>全部业务工具的 schema 实测约 <b>4100 token，占 {@code context.max-token} 默认值的 52%</b>
 * （{@code ToolSurfaceCostTest} 把这个数字固定了下来）——每一轮对话，一半以上的上下文预算
 * 被工具定义占掉，留给系统提示词、对话历史与知识注入的不到一半。而一个只做售后的部署，
 * 售前导购那几个工具从头到尾用不上，却每轮都在付这份成本。</p>
 *
 * <h3>为什么是静态配置而不是按意图动态激活</h3>
 * <p>动态激活的风险是<b>判错就办不了事</b>：用户说"我要退货"被判成咨询，退款工具没激活，
 * 模型只能跟他讲政策而办不了退款——那比多几个工具严重得多。而项目的意图分类
 * （{@code fastRouteIntent}）返回的是一个 {@code Optional<String>}，<b>没有置信度</b>，
 * 无法据此判断"这次判定可不可信"。</p>
 *
 * <p>静态配置零判错风险：运维明确知道自己的部署有没有售前导购业务。
 * 按意图动态激活需要先给意图分类补上置信度，是独立的一件事。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
public class ToolSurfaceProperties {

    /**
     * 本部署<b>不</b>注册的业务工具组（取值见 {@code ToolRegistrar} 的 {@code GROUP_*}）。
     *
     * <p>默认空——不改变任何既有部署的行为。填了哪个组，那个组的工具就不会进 Toolkit，
     * 模型完全看不到它们，相应的 token 也就不用付。</p>
     *
     * <p><b>转人工组不可停用</b>，见 {@code ToolRegistrar}：把它关掉，
     * 用户就被困在智能体里出不来了。</p>
     */
    private List<String> disabledGroups = new ArrayList<>();
}
