package com.richard.fyoung.customerwork.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MockProductBackend;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具面的实际开销：把「7 个业务组永久全激活」这件事量出来。
 *
 * <h3>为什么先量再改</h3>
 * <p>报告 P1-8 说「工具面完全不收敛」，方向没错，但<b>收敛的三条路各有明确代价</b>：</p>
 * <ul>
 *   <li><b>按意图静态激活组</b>：意图判错就拿不到需要的工具——用户说"我要退货"被判成咨询，
 *       退款工具没激活，模型直接办不了事。这比多几个工具严重得多；</li>
 *   <li><b>meta-tool 动态装备</b>（框架自带，项目默认关闭）：每轮先找工具再用工具，
 *       多一次模型往返，首字延迟明显增加，而客服场景对首字延迟很敏感；</li>
 *   <li><b>保持现状</b>：每轮请求都带上全部 schema。</li>
 * </ul>
 *
 * <p>选哪条取决于「全部 schema 到底占多少」——这个数字项目此前没有。本测试把它固定下来，
 * 并在它显著变化时提醒：新增工具是有代价的，代价落在每一轮对话上。</p>
 *
 * <p><b>另有一处已排除的猜测</b>：{@code ContextBudgetMiddleware} 是按<b>消息条数</b>裁剪
 * （{@code budgetMaxMessages}），不按 token 算，因此不存在"预算被 schema 吃掉"的问题。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ToolSurfaceCostTest {

    /**
     * 中文 token 粗估系数：1 个 token 约 1.5 个字符。
     *
     * <p>各家分词器不同，这里只需要一个量级正确的数——用来回答"占上下文的几成"，
     * 不用于任何计费或裁剪判定。</p>
     */
    private static final double CHARS_PER_TOKEN = 1.5;

    /** 与 {@code customer-work.context.max-token} 的默认值一致。 */
    private static final int CONTEXT_MAX_TOKEN = 8000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("量出全部业务工具 schema 的规模与上下文占比")
    void measureToolSchemaCost() throws Exception {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();
        registrar().registerBusinessTools(toolkit, "u1:conv-1");

        List<ToolSchema> schemas = toolkit.getToolSchemas(List.of());
        String json = objectMapper.writeValueAsString(schemas);
        int chars = json.length();
        int estimatedTokens = (int) Math.round(chars / CHARS_PER_TOKEN);
        double share = (double) estimatedTokens / CONTEXT_MAX_TOKEN;

        System.out.printf("[工具面开销] 工具数=%d schema 字符=%d 估算 token≈%d 占 max-token(%d) 约 %.0f%%%n",
            schemas.size(), chars, estimatedTokens, CONTEXT_MAX_TOKEN, share * 100);

        assertTrue(schemas.size() >= 15,
            "业务工具数量远低于预期，注册链路可能没跑全：" + schemas.size());
    }

    /**
     * 工具数量的护栏。
     *
     * <p>每加一个工具，代价落在<b>每一轮对话</b>的每一次模型调用上——而加工具的人
     * 通常只看到自己那一个。这条断言不阻止扩张，只要求扩张是被看见的：
     * 突破上限时先回答一句「这些工具是不是都需要在每轮都暴露给模型」。</p>
     */
    @Test
    @DisplayName("业务工具总数不超过上限，扩张必须是被看见的")
    void toolCountStaysBounded() {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();
        registrar().registerBusinessTools(toolkit, "u1:conv-1");

        int count = toolkit.getToolSchemas(List.of()).size();

        assertTrue(count <= 30,
            "业务工具已达 " + count + " 个。每一个都会出现在每一轮对话的请求里，"
                + "先回答：它们是不是都需要在每轮都暴露给模型？"
                + "确实需要就调高这个上限，并在报告 P1-8 里记一笔。");
    }

    @Test
    @DisplayName("停用工具组后，那些工具对模型完全不可见，token 也不用付")
    void disabledGroupsAreNotExposed() {
        Toolkit full = new DefaultActiveGroupsToolkit();
        registrar().registerBusinessTools(full, "u1:conv-1");
        int fullCount = full.getToolSchemas(List.of()).size();

        Toolkit trimmed = new DefaultActiveGroupsToolkit();
        registrar().registerBusinessTools(trimmed, "u1:conv-1",
            Set.of(ToolRegistrar.GROUP_PRESALE, ToolRegistrar.GROUP_MEMBER));
        List<ToolSchema> remaining = trimmed.getToolSchemas(List.of());

        assertTrue(remaining.size() < fullCount,
            "停用了两个组，工具数却没变——配置没生效，那份 token 还在每轮付着");
        assertTrue(remaining.stream().noneMatch(s -> s.getName().contains("Points")
                || s.getName().contains("MemberLevel")),
            "会员组已停用，它的工具却还暴露给模型：" + names(remaining));
    }

    /**
     * 转人工组不可停用——这是安全底线，不是配置项。
     *
     * <p>把它关掉，用户就被困在智能体里出不来了。所以这里硬性忽略而不是靠文档提醒：
     * 一个能把用户困住的开关，不该存在于配置文件里。</p>
     */
    @Test
    @DisplayName("配置里写了停用转人工也会被拒绝")
    void humanHandoffGroupCannotBeDisabled() {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();

        registrar().registerBusinessTools(toolkit, "u1:conv-1", Set.of(ToolRegistrar.GROUP_HUMAN));

        List<ToolSchema> schemas = toolkit.getToolSchemas(List.of());
        assertTrue(schemas.stream().anyMatch(s -> s.getName().toLowerCase().contains("human")
                || s.getName().toLowerCase().contains("transfer")),
            "转人工工具被配置停用了——用户将无法从智能体转到人工：" + names(schemas));
    }

    @Test
    @DisplayName("停用清单大小写与空白不敏感，配错格式不会静默失效")
    void disabledGroupsAreNormalized() {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();

        registrar().registerBusinessTools(toolkit, "u1:conv-1", Set.of("  PRESALE  "));

        assertTrue(toolkit.getToolSchemas(List.of()).stream()
                .noneMatch(s -> s.getName().contains("Product")),
            "大小写/空白导致停用配置没生效——运维会以为配了，而 token 照付");
    }

    private String names(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName).collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 构造与生产一致的注册器。
     *
     * <p><b>可选依赖必须一起装上</b>：{@code SlotFillingService} 是 {@code @Autowired(required = false)}
     * 注入的，不装它就少注册一个工具——那样这里量出的 token 数会<b>比生产环境低</b>，
     * 而新加的工具恰恰绕过了这道开销护栏。本测试是"加工具有代价"的唯一提醒，
     * 它自己漏算就等于没有。</p>
     */
    private ToolRegistrar registrar() {
        ToolRegistrar registrar = new ToolRegistrar(new MockOrderBackend(), new MockAfterSalesBackend(),
            new MockKnowledgeBackend(), new MockProductBackend(), new MockMemberBackend(),
            new MockComplaintBackend(), new PendingApprovalService(), new HandoffService(), null);
        registrar.setSlotFillingService(new SlotFillingService());
        return registrar;
    }
}
