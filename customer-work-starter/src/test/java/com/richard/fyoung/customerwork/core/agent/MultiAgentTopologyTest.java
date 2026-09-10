package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.MultiAgentProperties;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 编排拓扑可配（能力差距 P1-15）。
 *
 * <h3>改造前的三处硬编码</h3>
 * <p>专家清单与提示词写死在 {@code buildSpecialists()}、意图到专家的映射写死成一个 switch、
 * 快车道关键词写死成一张静态表。改一句提示词、调一个业务触发词、增删一个专家，
 * 都要改代码重新发版。</p>
 *
 * <h3>这一组测试最要紧的一条是「默认值没变」</h3>
 * <p>把硬编码搬进配置时最容易发生的事，是顺手把提示词润色一下、把关键词补两个——
 * 那等于在一次配置化重构里夹带一次行为变更，而且没人会注意到。
 * 下面把改造前的原文逐字钉在断言里。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MultiAgentTopologyTest {

    /** 改造前 {@code buildSpecialists()} 里的三段提示词原文。 */
    private static final Map<String, String> LEGACY_PROMPTS = Map.of(
        "OrderExpert",
        "你是订单与物流专家。只就订单状态、物流轨迹、金额等问题作答，调用订单工具查询后回答；与你无关的问题简要说明并建议转交对应专家。",
        "AfterSalesExpert",
        "你是售后与退款专家。处理退款资格校验与退款工单；涉及资金只生成待人工确认工单，绝不承诺已打款。",
        "KnowledgeExpert",
        "你是政策咨询专家。依据知识库回答退换货、发票、运费等政策问题，并保留来源标注。");

    /** 改造前 {@code FAST_ROUTE_KEYWORDS} 的原文。 */
    private static final Map<String, List<String>> LEGACY_KEYWORDS = Map.of(
        "refund", List.of("退款", "退货", "退钱", "申请退", "已读不退"),
        "order", List.of("物流", "快递", "到哪了", "发货", "签收", "运单", "几天到"),
        "complaint", List.of("投诉", "差评", "举报", "态度", "315", "曝光"),
        "consult", List.of("发票", "运费", "政策", "几天无理由", "保修", "能不能开票"));

    @Test
    @DisplayName("默认专家逐字等于改造前的硬编码")
    void defaultExpertsMatchLegacyHardcoding() {
        List<MultiAgentProperties.Expert> experts = new CustomerWorkProperties()
            .getMultiAgent().getExperts();

        assertEquals(3, experts.size(), "默认专家数量变了");
        for (MultiAgentProperties.Expert expert : experts) {
            String legacy = LEGACY_PROMPTS.get(expert.getName());
            assertEquals(legacy, expert.getSysPrompt(),
                expert.getName() + " 的提示词与改造前不一致——配置化重构里不该夹带行为变更。"
                    + "确实要改提示词的话，那是另一件事，请单独提交并说明预期影响");
        }
    }

    @Test
    @DisplayName("默认路由关键词逐字等于改造前的静态表")
    void defaultRouteKeywordsMatchLegacyTable() {
        Map<String, List<String>> keywords = new CustomerWorkProperties()
            .getMultiAgent().getRouteKeywords();

        assertEquals(LEGACY_KEYWORDS.keySet(), keywords.keySet(), "意图集合变了");
        LEGACY_KEYWORDS.forEach((intent, words) ->
            assertEquals(words, keywords.get(intent), intent + " 的触发词与改造前不一致"));
    }

    /**
     * 快车道关键词必须挂在<b>意图</b>上，不能挂在专家上。
     *
     * <p>售后专家同时认领 refund 与 complaint。按专家分组的话，「我要退款而且要投诉」
     * 会命中同一个专家、被当成唯一命中直路由；而原实现命中两类意图，交给 LLM 分诊。
     * 这条钉住那个区别——改造过程中差点写成按专家分组。</p>
     */
    @Test
    @DisplayName("同一专家的两个意图各自独立，跨意图命中仍交 LLM")
    void keywordsAreGroupedByIntentNotByExpert() {
        MultiAgentOrchestrator orchestrator = orchestrator(new CustomerWorkProperties());

        assertEquals("refund", orchestrator.fastRouteIntent("我要退款").orElse(null));
        assertEquals("complaint", orchestrator.fastRouteIntent("我要投诉").orElse(null));
        assertTrue(orchestrator.fastRouteIntent("我要退款而且要投诉").isEmpty(),
            "命中两类意图仍应交 LLM 慢车道——按专家分组会让这句直路由到售后专家");
    }

    @Test
    @DisplayName("新增一个专家就能参与路由，不用改代码")
    void newExpertParticipatesInRouting() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().getExperts().add(
            expert("PresaleExpert", "你是售前导购专家。", List.of("knowledge"), List.of("presale"), 40));
        props.getMultiAgent().getRouteKeywords().put("presale", List.of("推荐", "有没有货"));
        MultiAgentOrchestrator orchestrator = orchestrator(props);

        assertEquals("presale", orchestrator.fastRouteIntent("有没有货").orElse(null));
        List<ReActAgent> all = orchestrator.buildSpecialists();
        assertEquals(4, all.size(), "新增的专家没被建出来");
        assertEquals(Set.of("PresaleExpert"),
            names(orchestrator.expertsForIntent("presale", all)),
            "presale 意图没有路由到新专家");
        closeAll(all);
    }

    @Test
    @DisplayName("停用的专家不参与任何路由与广播")
    void disabledExpertIsExcluded() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().getExperts().stream()
            .filter(e -> "KnowledgeExpert".equals(e.getName()))
            .forEach(e -> e.setEnabled(false));
        MultiAgentOrchestrator orchestrator = orchestrator(props);

        List<ReActAgent> all = orchestrator.buildSpecialists();
        assertEquals(2, all.size());
        assertFalse(names(all).contains("KnowledgeExpert"));
        // 没有专家认领 consult 了，退回广播全部——宁可多问几个也不要答不上来
        assertEquals(2, orchestrator.expertsForIntent("consult", all).size());
        closeAll(all);
    }

    @Test
    @DisplayName("order 决定 sequential 的流转顺序")
    void orderDrivesSequentialTopology() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().getExperts().forEach(e -> {
            if ("KnowledgeExpert".equals(e.getName())) {
                e.setOrder(1);
            }
        });

        List<String> ordered = orchestrator(props).enabledExperts().stream()
            .map(MultiAgentProperties.Expert::getName).toList();

        assertEquals(List.of("KnowledgeExpert", "OrderExpert", "AfterSalesExpert"), ordered);
    }

    /**
     * 引用编排器手里没有的工具组时，专家照建但没有那个工具。
     *
     * <p>关键是它<b>不静默</b>：实现里记了 error 码 {@code MAS-TOOL-GROUP-UNKNOWN}。
     * 「配了不生效」比没有这个配置项更糟——这种错配只会表现为「这个专家什么都查不到」，
     * 从日志里看不出原因。</p>
     */
    @Test
    @DisplayName("未知工具组不会让整个专家建不出来")
    void unknownToolGroupDoesNotBreakExpert() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getMultiAgent().getExperts().add(
            expert("MemberExpert", "你是会员专家。", List.of("member"), List.of("member"), 40));

        List<ReActAgent> all = orchestrator(props).buildSpecialists();

        assertEquals(4, all.size(), "未知工具组把整个专家搞没了");
        assertTrue(names(all).contains("MemberExpert"));
        closeAll(all);
    }

    private MultiAgentOrchestrator orchestrator(CustomerWorkProperties props) {
        return new MultiAgentOrchestrator(mock(Model.class), props,
            mock(OrderBackend.class), mock(AfterSalesBackend.class), mock(KnowledgeBackend.class));
    }

    private static MultiAgentProperties.Expert expert(String name, String prompt,
                                                     List<String> toolGroups,
                                                     List<String> intents, int order) {
        MultiAgentProperties.Expert expert = new MultiAgentProperties.Expert();
        expert.setName(name);
        expert.setSysPrompt(prompt);
        expert.setToolGroups(new ArrayList<>(toolGroups));
        expert.setIntents(new ArrayList<>(intents));
        expert.setOrder(order);
        return expert;
    }

    private static Set<String> names(List<ReActAgent> agents) {
        return agents.stream().map(ReActAgent::getName).collect(Collectors.toSet());
    }

    private static void closeAll(List<ReActAgent> agents) {
        agents.forEach(agent -> AgentResourceCloser.closeQuietly(agent, "topology-test"));
    }
}
