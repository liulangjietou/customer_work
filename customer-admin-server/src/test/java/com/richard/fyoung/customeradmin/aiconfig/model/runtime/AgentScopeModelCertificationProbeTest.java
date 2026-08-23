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
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
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
        verify(modelFactory, never()).buildModel(anyString(), anyString(), anyString(), anyString());
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
            Flux.just(response(TextBlock.builder().text("{\"status\":\"ok\"}").build()))));
        when(modelFactory.buildModel(anyString(), anyString(), anyString(), anyString())).thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(32_000), SECRET_VALUE, request(8_192));

        assertEquals(3, model.calls());
        assertEquals(16_000, result.verifiedContextTokens());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "CONNECTIVITY").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "LATENCY").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "STREAMING").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "TOOL_CALL").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "STRUCTURED_OUTPUT").status());
        assertEquals(ModelCertificationCheckStatus.PASSED.name(), check(result, "CONTEXT_WINDOW").status());
        verify(modelFactory, times(3)).testConnectivity(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1");
        verify(modelFactory).buildModel(
            "anthropic", "https://model.example/v1", SECRET_VALUE, "model-v1");
        assertSensitiveTextAbsent(result);
    }

    @Test
    void capabilityBoundaries_shouldFailWithoutExposingReturnedModelContent() {
        when(modelFactory.testConnectivity(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(successfulConnectivity());
        StubModel model = new StubModel(4_096, true, List.of(
            Flux.just(response(TextBlock.builder().text(THIRD_PARTY_BODY).build())),
            Flux.just(response(new ToolUseBlock("call-1", "wrong_tool", Map.of()))),
            Flux.just(response(TextBlock.builder().text(THIRD_PARTY_BODY).build()))));
        when(modelFactory.buildModel(anyString(), anyString(), anyString(), anyString())).thenReturn(model);

        ModelCertificationProbe.ProbeResult result = probe.probe(
            deployment(), asset(32_000), SECRET_VALUE, request(8_192));

        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STREAMING").status());
        assertEquals("1 chunks", check(result, "STREAMING").measuredValue());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "TOOL_CALL").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "STRUCTURED_OUTPUT").status());
        assertEquals(ModelCertificationCheckStatus.FAILED.name(), check(result, "CONTEXT_WINDOW").status());
        assertEquals(4_096, result.verifiedContextTokens());
        assertSensitiveTextAbsent(result);
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

    private AiModelAsset asset(int contextWindow) {
        AiModelAsset asset = new AiModelAsset();
        asset.setContextWindow(contextWindow);
        return asset;
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

    private static final class StubModel implements Model {

        private final int contextWindow;
        private final boolean structuredOutput;
        private final List<Flux<ChatResponse>> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private StubModel(int contextWindow, boolean structuredOutput,
                          List<Flux<ChatResponse>> responses) {
            this.contextWindow = contextWindow;
            this.structuredOutput = structuredOutput;
            this.responses = responses;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
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
    }
}
