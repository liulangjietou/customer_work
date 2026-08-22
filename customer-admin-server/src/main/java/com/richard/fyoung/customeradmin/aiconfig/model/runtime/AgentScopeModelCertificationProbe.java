package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationCheckStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationCheckVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 基于生产相同 AgentScope Model 的认证探测器。异常只转成固定摘要，避免第三方响应携带凭据进入认证报告。
 */
@Component
public class AgentScopeModelCertificationProbe implements ModelCertificationProbe {

    private static final int LATENCY_SAMPLES = 3;
    private static final int STREAM_MIN_CHUNKS = 2;
    private static final Duration CAPABILITY_TIMEOUT = Duration.ofSeconds(20);
    private static final String TOOL_NAME = "certification_echo";

    private final AdminModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    public AgentScopeModelCertificationProbe(AdminModelFactory modelFactory, ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProbeResult probe(AiModelConfig deployment, AiModelAsset asset, String secretValue,
                             ModelCertificationRequest request) {
        List<ModelCertificationCheckVO> checks = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        boolean connected = probeConnectivity(deployment, secretValue, checks, latencies);
        long p95 = percentile95(latencies);
        checks.add(check("LATENCY", "基础延迟阈值",
            connected && p95 <= request.maxLatencyMs(), String.valueOf(p95),
            "≤ " + request.maxLatencyMs() + " ms",
            connected && p95 <= request.maxLatencyMs() ? "基础延迟满足上线门槛" : "基础延迟超过上线门槛"));

        if (!connected) {
            addUnavailableCapabilityChecks(checks, request);
            checks.add(failed("CONTEXT_WINDOW", "上下文窗口", null,
                "≥ " + request.requiredContextTokens(), "连通性失败，无法确认上下文窗口"));
            return new ProbeResult(checks, p95, null);
        }

        Model model;
        try {
            model = modelFactory.buildModel(protocol(deployment), deployment.getBaseUrl(), secretValue,
                deployment.getModel());
        } catch (Exception e) {
            addUnavailableCapabilityChecks(checks, request);
            checks.add(failed("CONTEXT_WINDOW", "上下文窗口", null,
                "≥ " + request.requiredContextTokens(), "模型运行时构建失败"));
            return new ProbeResult(checks, p95, null);
        }

        checks.add(probeStreaming(model, request.streamingRequired()));
        checks.add(probeToolCall(model, request.toolCallRequired()));
        checks.add(probeStructuredOutput(model, request.structuredOutputRequired()));
        int verifiedContext = verifiedContext(model, asset);
        checks.add(check("CONTEXT_WINDOW", "上下文窗口",
            verifiedContext >= request.requiredContextTokens(), String.valueOf(verifiedContext),
            "≥ " + request.requiredContextTokens(), verifiedContext >= request.requiredContextTokens()
                ? "运行时能力与资产声明满足窗口要求" : "运行时能力或资产声明低于窗口要求"));
        return new ProbeResult(checks, p95, verifiedContext > 0 ? verifiedContext : null);
    }

    private boolean probeConnectivity(AiModelConfig deployment, String secretValue,
                                      List<ModelCertificationCheckVO> checks, List<Long> latencies) {
        for (int i = 0; i < LATENCY_SAMPLES; i++) {
            long started = System.nanoTime();
            ModelTestResult result = modelFactory.testConnectivity(protocol(deployment), deployment.getBaseUrl(),
                secretValue, deployment.getModel());
            latencies.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
            if (result.testStatus() != ConnectivityTestStatus.SUCCESS) {
                checks.add(failed("CONNECTIVITY", "连通性", "sample " + (i + 1),
                    LATENCY_SAMPLES + " 次全部成功", "模型端点连通性检查失败"));
                return false;
            }
        }
        checks.add(passed("CONNECTIVITY", "连通性", LATENCY_SAMPLES + " / " + LATENCY_SAMPLES,
            LATENCY_SAMPLES + " 次全部成功", "模型端点连通性检查通过"));
        return true;
    }

    private ModelCertificationCheckVO probeStreaming(Model model, boolean required) {
        if (!required) {
            return skipped("STREAMING", "流式输出", "本次认证未要求流式能力");
        }
        try {
            Msg prompt = Msg.builder().role(MsgRole.USER)
                .textContent("请逐项输出 1 到 30，并在每个数字后添加逗号。")
                .build();
            List<ChatResponse> chunks = model.stream(List.of(prompt), List.of(),
                    GenerateOptions.builder().stream(true).maxTokens(96).build())
                .collectList().block(CAPABILITY_TIMEOUT);
            int size = chunks == null ? 0 : chunks.size();
            return check("STREAMING", "流式输出", size >= STREAM_MIN_CHUNKS,
                size + " chunks", "≥ " + STREAM_MIN_CHUNKS + " chunks",
                size >= STREAM_MIN_CHUNKS ? "观察到多个流式分片" : "未观察到可验证的流式分片");
        } catch (Exception e) {
            return failed("STREAMING", "流式输出", null,
                "≥ " + STREAM_MIN_CHUNKS + " chunks", "流式能力调用失败");
        }
    }

    private ModelCertificationCheckVO probeToolCall(Model model, boolean required) {
        if (!required) {
            return skipped("TOOL_CALL", "工具调用", "本次认证未要求工具调用能力");
        }
        try {
            ToolSchema tool = ToolSchema.builder()
                .name(TOOL_NAME)
                .description("回显认证值")
                .parameters(Map.of(
                    "type", "object",
                    "properties", Map.of("value", Map.of("type", "string")),
                    "required", List.of("value")))
                .strict(true)
                .build();
            Msg prompt = Msg.builder().role(MsgRole.USER)
                .textContent("必须调用 certification_echo，并把 value 设为 ok。")
                .build();
            List<ChatResponse> responses = model.stream(List.of(prompt), List.of(tool),
                    GenerateOptions.builder().stream(false).maxTokens(64)
                        .toolChoice(new ToolChoice.Required()).build())
                .collectList().block(CAPABILITY_TIMEOUT);
            boolean matched = content(responses).stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .anyMatch(block -> TOOL_NAME.equals(block.getName()));
            return check("TOOL_CALL", "工具调用", matched, matched ? TOOL_NAME : "none", TOOL_NAME,
                matched ? "模型返回了强制工具调用" : "模型未返回要求的工具调用");
        } catch (Exception e) {
            return failed("TOOL_CALL", "工具调用", null, TOOL_NAME, "工具调用能力检查失败");
        }
    }

    private ModelCertificationCheckVO probeStructuredOutput(Model model, boolean required) {
        if (!required) {
            return skipped("STRUCTURED_OUTPUT", "结构化输出", "本次认证未要求结构化输出能力");
        }
        if (!model.supportsNativeStructuredOutput()) {
            return failed("STRUCTURED_OUTPUT", "结构化输出", "unsupported", "JSON Schema",
                "模型运行时声明不支持原生结构化输出");
        }
        try {
            JsonSchema schema = JsonSchema.builder()
                .name("certification_result")
                .description("模型认证结构化输出")
                .schema(Map.of(
                    "type", "object",
                    "properties", Map.of("status", Map.of("type", "string", "enum", List.of("ok"))),
                    "required", List.of("status"),
                    "additionalProperties", false))
                .strict(true)
                .build();
            Msg prompt = Msg.builder().role(MsgRole.USER).textContent("返回 status=ok。不要输出其它字段。").build();
            List<ChatResponse> responses = model.stream(List.of(prompt), List.of(),
                    GenerateOptions.builder().stream(false).maxTokens(32)
                        .responseFormat(ResponseFormat.jsonSchema(schema)).build())
                .collectList().block(CAPABILITY_TIMEOUT);
            boolean structuredBlock = content(responses).stream().anyMatch(DataBlock.class::isInstance);
            String text = text(responses);
            boolean validJson = structuredBlock || isExpectedJson(text);
            return check("STRUCTURED_OUTPUT", "结构化输出", validJson,
                validJson ? "schema-valid" : "invalid", "JSON Schema",
                validJson ? "结构化输出符合认证 Schema" : "结构化输出不符合认证 Schema");
        } catch (Exception e) {
            return failed("STRUCTURED_OUTPUT", "结构化输出", null, "JSON Schema",
                "结构化输出能力检查失败");
        }
    }

    private boolean isExpectedJson(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node.isObject() && "ok".equals(node.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private int verifiedContext(Model model, AiModelAsset asset) {
        int runtime = Math.max(0, model.getContextWindowSize());
        int declared = asset.getContextWindow() == null ? 0 : Math.max(0, asset.getContextWindow());
        return runtime == 0 || declared == 0 ? 0 : Math.min(runtime, declared);
    }

    private void addUnavailableCapabilityChecks(List<ModelCertificationCheckVO> checks,
                                                ModelCertificationRequest request) {
        checks.add(request.streamingRequired()
            ? failed("STREAMING", "流式输出", null, "capability pass", "连通性失败，未执行流式检查")
            : skipped("STREAMING", "流式输出", "本次认证未要求流式能力"));
        checks.add(request.toolCallRequired()
            ? failed("TOOL_CALL", "工具调用", null, "capability pass", "连通性失败，未执行工具调用检查")
            : skipped("TOOL_CALL", "工具调用", "本次认证未要求工具调用能力"));
        checks.add(request.structuredOutputRequired()
            ? failed("STRUCTURED_OUTPUT", "结构化输出", null, "capability pass", "连通性失败，未执行结构化输出检查")
            : skipped("STRUCTURED_OUTPUT", "结构化输出", "本次认证未要求结构化输出能力"));
    }

    private List<ContentBlock> content(List<ChatResponse> responses) {
        if (CollectionUtils.isEmpty(responses)) {
            return List.of();
        }
        return responses.stream().filter(response -> response.getContent() != null)
            .flatMap(response -> response.getContent().stream()).toList();
    }

    private String text(List<ChatResponse> responses) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : content(responses)) {
            if (block instanceof TextBlock textBlock && StringUtils.hasText(textBlock.getText())) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    private long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return Long.MAX_VALUE;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1);
        return sorted.get(index);
    }

    private String protocol(AiModelConfig deployment) {
        return StringUtils.hasText(deployment.getProtocolAdapter())
            ? deployment.getProtocolAdapter() : deployment.getProvider();
    }

    private ModelCertificationCheckVO check(String code, String name, boolean passed,
                                            String measured, String threshold, String message) {
        return new ModelCertificationCheckVO(code, name, passed
            ? ModelCertificationCheckStatus.PASSED.name() : ModelCertificationCheckStatus.FAILED.name(),
            measured, threshold, message);
    }

    private ModelCertificationCheckVO passed(String code, String name, String measured,
                                             String threshold, String message) {
        return check(code, name, true, measured, threshold, message);
    }

    private ModelCertificationCheckVO failed(String code, String name, String measured,
                                             String threshold, String message) {
        return check(code, name, false, measured, threshold, message);
    }

    private ModelCertificationCheckVO skipped(String code, String name, String message) {
        return new ModelCertificationCheckVO(code, name, ModelCertificationCheckStatus.SKIPPED.name(),
            null, null, message);
    }
}
