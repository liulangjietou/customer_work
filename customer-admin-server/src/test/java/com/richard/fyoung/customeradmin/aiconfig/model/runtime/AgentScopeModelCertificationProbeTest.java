package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationCheckStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationCheckVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AgentScopeModelCertificationProbe} 的真实能力证据与脱敏边界测试。 */
class AgentScopeModelCertificationProbeTest {

    private static final String SECRET_VALUE = "sk-certification-secret";
    private static final String THIRD_PARTY_BODY = "provider raw body contains sk-live-credential";

    private AdminModelFactory modelFactory;
    private AgentScopeModelCertificationProbe probe;

    @BeforeEach
    void setUp() {
        modelFactory = mock(AdminModelFactory.class);
        probe = new AgentScopeModelCertificationProbe(modelFactory, new ObjectMapper());
    }

    @Test
    void firstConnectivityFailure_shouldFailClosedWithoutBuildingModelOrLeakingProviderBody() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ModelTestResult(
                ConnectivityTestStatus.FAILED, LocalDateTime.now(), THIRD_PARTY_BODY));

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(32_000), SECRET_VALUE, request(8_192));

        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "CONNECTIVITY").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "LATENCY").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STREAMING").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "TOOL_CALL").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STRUCTURED_OUTPUT").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "CONTEXT_WINDOW").status());
        assertNull(result.verifiedContextTokens());
        verify(modelFactory, times(1)).testConnectivity(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1");
        verify(modelFactory, never()).buildModelWithWindow(
            anyString(), anyString(), anyString(), anyString(), any());
        assertSensitiveTextAbsent(result);
    }

    @Test
    void successfulProbe_shouldVerifyStreamingToolStructuredOutputAndContextIntersection() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = new StubModel(16_000, true, List.of(
            Flux.just(response(TextBlock.builder().text("chunk-1").build()),
                response(TextBlock.builder().text("chunk-2").build())),
            Flux.just(response(new ToolUseBlock(
                "call-1", "certification_echo", Map.of("value", "ok")))),
            Flux.just(response(TextBlock.builder().text("{\"status\":\"ok\"}").build())),
            Flux.just(usageResponse(8_400))));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(32_000), SECRET_VALUE, request(8_192));

        assertEquals(4, model.calls());
        assertEquals(32_000, result.verifiedContextTokens());
        assertEquals("8400 tokens", check(result, "CONTEXT_WINDOW").measuredValue());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "CONNECTIVITY").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "LATENCY").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "STREAMING").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "TOOL_CALL").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "STRUCTURED_OUTPUT").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "CONTEXT_WINDOW").status());
        verify(modelFactory, times(3)).testConnectivity(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1");
        verify(modelFactory).buildModelWithWindow(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1", 32_000);
        assertSensitiveTextAbsent(result);
    }

    @Test
    void capabilityBoundaries_shouldFailWithoutExposingReturnedModelContent() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = new StubModel(4_096, true, List.of(
            Flux.just(response(TextBlock.builder().text(THIRD_PARTY_BODY).build())),
            Flux.just(response(new ToolUseBlock("call-1", "wrong_tool", Map.of()))),
            Flux.just(response(TextBlock.builder().text(THIRD_PARTY_BODY).build())),
            Flux.error(new IllegalStateException(THIRD_PARTY_BODY))));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(32_000), SECRET_VALUE, request(8_192));

        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STREAMING").status());
        assertEquals("1 chunks", check(result, "STREAMING").measuredValue());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "TOOL_CALL").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STRUCTURED_OUTPUT").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "CONTEXT_WINDOW").status());
        assertNull(result.verifiedContextTokens());
        assertSensitiveTextAbsent(result);
    }

    /**
     * 本次修复的直接回归：框架按模型名前缀推断窗口，推断表只有各厂商官方模型名，
     * glm / deepseek 这类走 OpenAI 兼容协议接入的第三方模型一律推断为 0。
     * 0 是「未知」不是「窗口为零」，不能据此判失败。
     */
    @Test
    void runtimeWindowUnknown_shouldStillPassOnDeclaredAssetAndRealProbe() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0, Flux.just(usageResponse(8_500)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), window.status());
        assertEquals("8500 tokens", window.measuredValue());
        assertEquals(1_000_000, result.verifiedContextTokens());
    }

    /** 资产声明才是窗口的权威来源；没登记就没有可校验的依据，此时才该判失败。 */
    @Test
    void assetWithoutDeclaredWindow_shouldFailWithoutSpendingProbeRequest() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0);
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(null), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), window.status());
        assertTrue(window.message().contains("未登记"));
        assertNull(result.verifiedContextTokens());
        assertEquals(3, model.calls());
        verify(modelFactory).buildModelWithWindow(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1", null);
    }

    /** 声明值本身就低于门槛时不必再发大请求，直接判失败并如实报出声明值。 */
    @Test
    void declaredWindowBelowThreshold_shouldFailWithoutSpendingProbeRequest() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0);
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(4_096), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), window.status());
        assertEquals("4096", window.measuredValue());
        assertEquals(4_096, result.verifiedContextTokens());
        assertEquals(3, model.calls());
    }

    /** 实测请求被端点拒绝（输入超出真实窗口）即为不通过，且不得回显第三方响应体。 */
    @Test
    void rejectedContextProbe_shouldFailWithoutLeakingProviderBody() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0, Flux.error(new IllegalStateException(THIRD_PARTY_BODY)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), window.status());
        assertNull(result.verifiedContextTokens());
        assertSensitiveTextAbsent(result);
    }

    /** 兼容网关可能不回 usage：请求成功即视为接受了这段输入，但实测值要标注成估算。 */
    @Test
    void endpointWithoutUsage_shouldPassAndMarkMeasurementApproximated() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0,
            Flux.just(response(TextBlock.builder().text("ok").build())));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), window.status());
        assertEquals("8192 tokens (估算)", window.measuredValue());
    }

    /** 门槛高于实测上限时只填充到上限，摘要必须写明实测覆盖到哪、其余依据什么。 */
    @Test
    void thresholdAboveProbeCeiling_shouldStateProbeCoverageInEvidence() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0, Flux.just(usageResponse(33_000)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(200_000));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), window.status());
        assertTrue(window.message().contains("实测上限 32768"));
        assertTrue(window.message().contains("1000000"));
        // 填充按实测上限而不是门槛构造，否则一次认证要发 20 万 token
        assertTrue(model.lastPromptLength() < 200_000);
        assertTrue(model.lastPromptLength() > 32_768);
    }

    /**
     * 各家 tokenizer 的字符/token 比不同，首轮填充可能不到目标量。
     * 这时要按端点回报的真实比率补填一次，而不是拿偏低的实测值当证据。
     */
    @Test
    void shortFirstMeasurement_shouldRefillByReportedRatioAndPass() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0,
            Flux.just(usageResponse(4_000)), Flux.just(usageResponse(8_400)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), window.status());
        assertEquals("8400 tokens", window.measuredValue());
        // 三次能力探测 + 两次上下文实测
        assertEquals(5, model.calls());
    }

    /** 补填一轮后仍达不到目标：证据不足以支撑门槛，判失败而不是拿这个数字虚过。 */
    @Test
    void persistentlyShortMeasurement_shouldFailInsteadOfPassingOnWeakEvidence() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0,
            Flux.just(usageResponse(3_000)), Flux.just(usageResponse(3_100)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        ModelCertificationCheckVO window = check(result, "CONTEXT_WINDOW");
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), window.status());
        assertEquals("3100 tokens", window.measuredValue());
        assertTrue(window.message().contains("不足以证明"));
        assertNull(result.verifiedContextTokens());
    }

    /** 填充不能靠重复字符：BPE 会把它压成极少 token，实际发送量远低于目标，实测就成了假阳性。 */
    @Test
    void contextProbeFiller_shouldVaryContentInsteadOfRepeatingOneCharacter() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = capableModel(0, Flux.just(usageResponse(8_500)));
        when(modelFactory.buildModelWithWindow(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(model);

        probe.probe(deployment(), asset(1_000_000), SECRET_VALUE, request(8_192));

        String filler = model.lastPrompt();
        assertTrue(filler.contains("seg0 "));
        assertTrue(filler.contains("seg1000 "));
        assertFalse(containsSensitiveText(filler));
    }

    private ModelTestResult successfulConnectivity() {
        return new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null);
    }

    private AiModelConfig deployment() {
        AiModelConfig deployment = new AiModelConfig();
        deployment.setProvider("openai");
        deployment.setProtocolAdapter("anthropic");
        deployment.setBaseUrl("https://model.example/v1");
        deployment.setModel("model-v1");
        return deployment;
    }

    private AiModelAsset asset(Integer contextWindow) {
        AiModelAsset asset = new AiModelAsset();
        asset.setContextWindow(contextWindow);
        return asset;
    }

    /** 三项能力探测一律通过的 stub，后接调用方给定的上下文实测响应（可给多轮，覆盖补填）。 */
    @SafeVarargs
    private StubModel capableModel(int runtimeWindow, Flux<ChatResponse>... contextProbes) {
        List<Flux<ChatResponse>> responses = new ArrayList<>(capabilityResponses());
        responses.addAll(List.of(contextProbes));
        return new StubModel(runtimeWindow, true, responses);
    }

    private List<Flux<ChatResponse>> capabilityResponses() {
        return List.of(
            Flux.just(response(TextBlock.builder().text("chunk-1").build()),
                response(TextBlock.builder().text("chunk-2").build())),
            Flux.just(response(new ToolUseBlock("call-1", "certification_echo", Map.of("value", "ok")))),
            Flux.just(response(TextBlock.builder().text("{\"status\":\"ok\"}").build())));
    }

    private ModelCertificationRequest request(int requiredContextTokens) {
        return new ModelCertificationRequest(requiredContextTokens, 10_000L,
            BigDecimal.TEN, BigDecimal.TEN, 30, true, true, true);
    }

    private ModelCertificationCheckVO check(ModelCertificationProbe.ProbeResult result, String code) {
        return result.checks().stream()
            .filter(item -> code.equals(item.code()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing certification check: " + code));
    }

    private void assertSensitiveTextAbsent(ModelCertificationProbe.ProbeResult result) {
        for (ModelCertificationCheckVO check : result.checks()) {
            assertFalse(containsSensitiveText(check.measuredValue()));
            assertFalse(containsSensitiveText(check.threshold()));
            assertFalse(containsSensitiveText(check.message()));
        }
    }

    private boolean containsSensitiveText(String value) {
        return value != null && (value.contains(SECRET_VALUE) || value.contains(THIRD_PARTY_BODY));
    }

    private ChatResponse response(ContentBlock... content) {
        return new ChatResponse("response", List.of(content), null, null, "stop");
    }

    /** 端点回报输入 token 数的响应——上下文窗口实测的权威证据就取自这里。 */
    private ChatResponse usageResponse(int inputTokens) {
        return new ChatResponse("response", List.of(TextBlock.builder().text("ok").build()),
            new ChatUsage(inputTokens, 2, 0, 0.1d), null, "stop");
    }

    private static final class StubModel implements Model {

        private final int contextWindow;
        private final boolean structuredOutput;
        private final List<Flux<ChatResponse>> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String lastPrompt = "";

        private StubModel(int contextWindow, boolean structuredOutput,
                          List<Flux<ChatResponse>> responses) {
            this.contextWindow = contextWindow;
            this.structuredOutput = structuredOutput;
            this.responses = responses;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            lastPrompt = messages.isEmpty() ? "" : messages.get(0).getTextContent();
            int index = calls.getAndIncrement();
            return index < responses.size()
                ? responses.get(index)
                : Flux.error(new IllegalStateException("unexpected extra certification probe"));
        }

        @Override
        public String getModelName() {
            return "certification-stub";
        }

        @Override
        public int getContextWindowSize() {
            return contextWindow;
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return structuredOutput;
        }

        private int calls() {
            return calls.get();
        }

        private String lastPrompt() {
            return lastPrompt;
        }

        private int lastPromptLength() {
            return lastPrompt.length();
        }
    }
}
