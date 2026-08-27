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

    /**
     * 上下文窗口实测的填充上限。门槛可能声明到百万级，真按那个量填充既慢又费；
     * 超过上限的部分退回资产声明，并在证据摘要里写明实测只覆盖到哪。
     */
    private static final int MAX_PROBE_CONTEXT_TOKENS = 32_768;

    /** 实测请求要发大段输入，超时单独放宽，不跟能力探测共用 20s。 */
    private static final Duration CONTEXT_PROBE_TIMEOUT = Duration.ofSeconds(60);

    /** 实测请求只要模型开口即可，输出压到最小，避免为验证输入长度付出输出成本。 */
    private static final int CONTEXT_PROBE_MAX_TOKENS = 8;

    /** 填充文本的字符/token 估算比。各家 tokenizer 差异大，首次按它估，不够再按端点回报的真实比率补一次。 */
    private static final int CHARS_PER_TOKEN = 3;

    /** 补填的字符数上限（相对目标 token 数）。防止端点回报异常时把填充撑到不可控的量。 */
    private static final int MAX_FILLER_CHARS_PER_TOKEN = 12;

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
            // 资产登记窗口是这个部署窗口能力的权威声明，建模时就注入，别让框架去猜模型名
            model = modelFactory.buildModelWithWindow(protocol(deployment), deployment.getBaseUrl(),
                secretValue, deployment.getModel(), declaredContext(asset));
        } catch (Exception e) {
            addUnavailableCapabilityChecks(checks, request);
            checks.add(failed("CONTEXT_WINDOW", "上下文窗口", null,
                "≥ " + request.requiredContextTokens(), "模型运行时构建失败"));
            return new ProbeResult(checks, p95, null);
        }

        checks.add(probeStreaming(model, request.streamingRequired()));
        checks.add(probeToolCall(model, request.toolCallRequired()));
        checks.add(probeStructuredOutput(model, request.structuredOutputRequired()));
        ContextWindowEvidence context = probeContextWindow(model, asset, request.requiredContextTokens());
        checks.add(context.check());
        return new ProbeResult(checks, p95, context.verifiedTokens());
    }

    /**
     * 上下文窗口实测：真发一次填充到门槛量级的请求，用端点自己回报的输入 token 数作为证据。
     *
     * <p>不再拿框架的 {@code getContextWindowSize()} 当运行时证据——那个值是按模型名前缀查硬编码表得来的，
     * 表里只有各厂商官方模型名，第三方模型（glm / deepseek / 自建网关）一律查不到返回 0，
     * 而 0 被当成「窗口为零」会让这类部署永远认证不过。</p>
     *
     * <p>判定顺序是先声明后实测：资产没登记窗口、或登记值本身低于门槛时直接判失败，
     * 省掉一次注定无意义的大请求。</p>
     */
    private ContextWindowEvidence probeContextWindow(Model model, AiModelAsset asset, int required) {
        String threshold = "≥ " + required;
        Integer declaredValue = declaredContext(asset);
        if (declaredValue == null) {
            return new ContextWindowEvidence(failed("CONTEXT_WINDOW", "上下文窗口", null, threshold,
                "模型资产未登记上下文窗口，无据可验"), null);
        }
        int declared = declaredValue;
        if (declared < required) {
            return new ContextWindowEvidence(failed("CONTEXT_WINDOW", "上下文窗口",
                String.valueOf(declared), threshold, "资产登记的上下文窗口低于门槛"), declared);
        }

        int target = Math.min(required, MAX_PROBE_CONTEXT_TOKENS);
        Integer measured;
        try {
            measured = measureAcceptedInputTokens(model, target);
        } catch (Exception e) {
            return new ContextWindowEvidence(failed("CONTEXT_WINDOW", "上下文窗口", null, threshold,
                "上下文窗口实测请求失败，端点未能接受 " + target + " token 输入"), null);
        }

        // 不回 usage 的兼容网关拿不到真实输入量，只能按发送量估算，实测值上标注出来
        boolean approximated = measured == null;
        int evidence = approximated ? target : measured;
        String measuredValue = evidence + (approximated ? " tokens (估算)" : " tokens");
        if (!approximated && measured < target) {
            // 填充补过一轮仍达不到目标：证据不足以支撑门槛，判失败而不是拿这个数字虚过
            return new ContextWindowEvidence(failed("CONTEXT_WINDOW", "上下文窗口", measuredValue,
                threshold, "实测输入仅 " + measured + " token，不足以证明满足 " + target + " token 门槛"),
                null);
        }
        String message = required > MAX_PROBE_CONTEXT_TOKENS
            ? "实测接受 " + evidence + " token 输入（实测上限 " + MAX_PROBE_CONTEXT_TOKENS
                + "），超出部分依据资产登记的 " + declared
            : "实测接受 " + evidence + " token 输入，满足窗口门槛";
        return new ContextWindowEvidence(
            passed("CONTEXT_WINDOW", "上下文窗口", measuredValue, threshold, message), declared);
    }

    /**
     * 发一次填充到目标量级的请求，返回端点回报的输入 token 数；端点不回 usage 时返回 {@code null}。
     * 请求被拒（输入超出真实窗口）时抛出，由调用方判失败。
     *
     * <p>各家 tokenizer 的字符/token 比差异很大，首轮按估算比填充可能不到目标量。
     * 这种情况下用端点自己回报的真实比率补填一次——拿一个偏低的实测值当证据就是虚过，
     * 而认证是门禁，虚过等于门禁失效。</p>
     */
    private Integer measureAcceptedInputTokens(Model model, int targetTokens) {
        String text = filler(targetTokens * CHARS_PER_TOKEN);
        Integer measured = sendContextProbe(model, text);
        if (measured == null || measured >= targetTokens) {
            return measured;
        }
        long adjustedChars = Math.min((long) text.length() * targetTokens / measured,
            (long) targetTokens * MAX_FILLER_CHARS_PER_TOKEN);
        if (adjustedChars <= text.length()) {
            return measured;
        }
        return sendContextProbe(model, filler((int) adjustedChars));
    }

    private Integer sendContextProbe(Model model, String text) {
        Msg prompt = Msg.builder().role(MsgRole.USER).textContent(text).build();
        List<ChatResponse> responses = model.stream(List.of(prompt), List.of(),
                GenerateOptions.builder().stream(false).maxTokens(CONTEXT_PROBE_MAX_TOKENS).build())
            .collectList().block(CONTEXT_PROBE_TIMEOUT);
        if (CollectionUtils.isEmpty(responses)) {
            throw new IllegalStateException("empty context probe response");
        }
        int inputTokens = responses.stream()
            .filter(response -> response.getUsage() != null)
            .mapToInt(response -> response.getUsage().getInputTokens())
            .max().orElse(0);
        return inputTokens > 0 ? inputTokens : null;
    }

    /**
     * 构造指定字符数的填充文本。刻意用递增编号而不是重复字符——重复字符会被 BPE 合并成极少 token，
     * 那样发出去的实际输入远小于目标，实测就成了假阳性。
     */
    private String filler(int targetChars) {
        StringBuilder text = new StringBuilder(targetChars + 64);
        text.append("以下为上线认证的上下文窗口实测填充内容，请只回复 ok。");
        int index = 0;
        while (text.length() < targetChars) {
            text.append("seg").append(index++).append(' ');
        }
        return text.toString();
    }

    private Integer declaredContext(AiModelAsset asset) {
        if (asset == null || asset.getContextWindow() == null || asset.getContextWindow() <= 0) {
            return null;
        }
        return asset.getContextWindow();
    }

    /** 检查项与落库的窗口结论；后者取通过校验的声明窗口，实测证据在检查项的实测值里。 */
    private record ContextWindowEvidence(ModelCertificationCheckVO check, Integer verifiedTokens) {
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
