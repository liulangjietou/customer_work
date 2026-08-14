package com.richard.fyoung.customerwork.capability.csat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSAT 对外 JSON 契约单测。
 *
 * <p><b>为什么需要这组用例</b>：{@link CsatServiceTest} 的 12 个用例全绿，却没能拦住一个真实故障——
 * Jackson 序列化 record 时<b>只认组件</b>，{@code csat()}/{@code responseRate()}/{@code averageScore()}
 * 这些普通方法不标 {@code @JsonProperty} 就不进 JSON。领域逻辑测试只验证"算得对不对"，
 * 而字段压根没下发这件事发生在序列化层，它照不到。</p>
 *
 * <p>后果远不止少个字段：前端 {@code summary.averageScore.toFixed()} 在 undefined 上抛错，
 * Vue 渲染中断，连 loading 都停在原地转圈，表面看像"接口没返回"，实际接口早已 200。
 * 给 record 新增派生方法时，这组用例是那条约定的守门人。</p>
 * @author owlzhangfq@gmail.com
 */
class CsatSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void summary_shouldExposeDerivedMetrics() throws Exception {
        // 4 次邀请、2 次回收、其中 1 次满意
        CsatSummary summary = new CsatSummary(4, 2, 1, 7);

        String json = mapper.writeValueAsString(summary);

        assertTrue(json.contains("\"csat\""), "满意率必须下发，它是这块看板的主指标");
        assertTrue(json.contains("\"responseRate\""), "回收率必须下发——没有它，CSAT 是个会骗人的数字");
        assertTrue(json.contains("\"averageScore\""), "平均分必须下发，前端会直接对它调 toFixed");
    }

    @Test
    void summary_derivedValues_shouldMatchDomainLogic() throws Exception {
        CsatSummary summary = new CsatSummary(4, 2, 1, 7);

        var node = mapper.readTree(mapper.writeValueAsString(summary));

        assertEquals(0.5d, node.get("csat").asDouble(), 1e-9);
        assertEquals(0.5d, node.get("responseRate").asDouble(), 1e-9);
        assertEquals(3.5d, node.get("averageScore").asDouble(), 1e-9);
    }

    @Test
    void survey_shouldExposeAnsweredAndSatisfied() throws Exception {
        CsatSurvey answered = CsatSurvey.invited("sess-1", "tenantA", 1000L)
            .withScore(5, "解决得很快", 2000L);

        var node = mapper.readTree(mapper.writeValueAsString(answered));

        // 漏标时前端按 answered 过滤会全部落空，列表永远"暂无数据"而接口其实是好的——最难查的那种
        assertTrue(node.has("answered"), "已评价标记必须下发，前端据此过滤明细列表");
        assertTrue(node.has("satisfied"), "满意标记必须下发");
        assertTrue(node.get("answered").asBoolean());
        assertTrue(node.get("satisfied").asBoolean());
    }

    @Test
    void pendingSurvey_shouldSerializeAsNotAnswered() throws Exception {
        CsatSurvey pending = CsatSurvey.invited("sess-2", "tenantA", 1000L);

        var node = mapper.readTree(mapper.writeValueAsString(pending));

        assertTrue(node.get("score").isNull(), "未评价时分数为 null，回收率的分母靠它区分");
        assertEquals(false, node.get("answered").asBoolean());
        assertEquals(false, node.get("satisfied").asBoolean());
    }
}
