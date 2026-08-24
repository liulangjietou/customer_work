package com.richard.fyoung.customerwork.data.calllog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * 单次调用的可变重放采集器。唯一的数据最小化与脱敏边界集中在本类，调用链只提交框架事件，
 * 不自行拼 JSON 或复制原始工具/RAG 内容。
 */
public final class AgentReplayCapture {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_REFERENCES = 20;
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
        "knowledge_base=(\\S+)\\s+doc_id=(\\S+)\\s+chunk_id=(\\S+)\\s+score=(\\S+)");
    private static final List<String> SENSITIVE_KEYS = List.of(
        "apikey", "api_key", "authorization", "cookie", "credential", "password", "secret", "token");

    private final AtomicInteger modelSequence = new AtomicInteger();
    private final AtomicInteger ragSequence = new AtomicInteger();
    private final AtomicInteger toolSequence = new AtomicInteger();
    private final List<AgentReplaySnapshot.ModelCallSnapshot> modelCalls = new CopyOnWriteArrayList<>();
    private final List<AgentReplaySnapshot.RagRetrievalSnapshot> ragRetrievals = new CopyOnWriteArrayList<>();
    private final List<AgentReplaySnapshot.ToolCallSnapshot> toolCalls = new CopyOnWriteArrayList<>();

    public static AgentReplayCapture from(RuntimeContext context) {
        if (context == null) {
            return null;
        }
        return context.get(AgentReplayCapture.class);
    }

    public static void bind(RuntimeContext context, AgentReplayCapture capture) {
        if (context != null && capture != null) {
            context.put(AgentReplayCapture.class, capture);
        }
    }

    /** 应在所有会改写 ModelCallInput 的中间件之后调用，确保拿到最终有效参数。 */
    public void recordModelCall(ModelCallInput input) {
        GenerateOptions options = input == null ? null : input.options();
        List<ToolSchema> schemas = input == null || input.tools() == null ? List.of() : input.tools();
        List<String> toolNames = schemas.stream().map(ToolSchema::getName).filter(name -> name != null).toList();
        int messageCount = input == null || input.messages() == null ? 0 : input.messages().size();
        modelCalls.add(new AgentReplaySnapshot.ModelCallSnapshot(
            modelSequence.incrementAndGet(),
            input == null || input.model() == null ? null : input.model().getModelName(),
            optionsSnapshot(options),
            messageCount,
            schemas.size(),
            toolNames,
            sha256(serializedBytes(input == null ? null : input.messages()))));
    }

    /** 记录一次 RAG 检索的脱敏结果；正文仅参与哈希，不进入快照。 */
    public void recordRag(String agentCode, String query, String block, boolean failed) {
        String status = failed ? "ERROR" : (block == null || block.isBlank() ? "MISS" : "HIT");
        ragRetrievals.add(new AgentReplaySnapshot.RagRetrievalSnapshot(
            ragSequence.incrementAndGet(), agentCode, status, sha256(bytes(query)),
            block == null ? 0 : block.length(), sha256(bytes(block)), references(block)));
    }

    public ToolBatchCapture beginTools(ActingInput input,
                                       Function<String, AgentCallKind> kindClassifier) {
        List<ToolUseBlock> calls = input == null || input.toolCalls() == null ? List.of() : input.toolCalls();
        List<ToolObservation> observations = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            if (call != null) {
                AgentCallKind kind = kindClassifier == null
                    ? AgentCallKind.TOOL : kindClassifier.apply(call.getName());
                observations.add(new ToolObservation(toolSequence.incrementAndGet(), call, kind));
            }
        }
        return new ToolBatchCapture(observations);
    }

    public AgentReplaySnapshot snapshot() {
        return new AgentReplaySnapshot(AgentReplaySnapshot.CURRENT_SCHEMA_VERSION,
            modelCalls, ragRetrievals, toolCalls);
    }

    private AgentReplaySnapshot.GenerateOptionsSnapshot optionsSnapshot(GenerateOptions options) {
        if (options == null) {
            return new AgentReplaySnapshot.GenerateOptionsSnapshot(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), List.of(), null, List.of());
        }
        ExecutionConfig execution = options.getExecutionConfig();
        Map<String, Object> body = sanitizeMap(options.getAdditionalBodyParams());
        return new AgentReplaySnapshot.GenerateOptionsSnapshot(
            options.getStream(), options.getTemperature(), options.getTopP(), options.getTopK(),
            options.getMaxTokens(), options.getMaxCompletionTokens(), options.getFrequencyPenalty(),
            options.getPresencePenalty(), options.getThinkingBudget(), options.getReasoningEffort(),
            options.getSeed(), options.getCacheControl(), options.getParallelToolCalls(),
            simpleType(options.getToolChoice()),
            options.getResponseFormat() == null ? null : options.getResponseFormat().getType(),
            durationMs(execution == null ? null : execution.getTimeout()),
            execution == null ? null : execution.getMaxAttempts(),
            durationMs(execution == null ? null : execution.getInitialBackoff()),
            durationMs(execution == null ? null : execution.getMaxBackoff()),
            execution == null ? null : execution.getBackoffMultiplier(),
            keys(options.getAdditionalHeaders()), keys(body), sha256(serializedBytes(body)),
            keys(options.getAdditionalQueryParams()));
    }

    private List<AgentReplaySnapshot.RagReferenceSnapshot> references(String block) {
        if (block == null || block.isBlank()) {
            return List.of();
        }
        List<AgentReplaySnapshot.RagReferenceSnapshot> result = new ArrayList<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(block);
        while (matcher.find() && result.size() < MAX_REFERENCES) {
            result.add(new AgentReplaySnapshot.RagReferenceSnapshot(
                matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        return List.copyOf(result);
    }

    private static Long durationMs(java.time.Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private static String simpleType(Object value) {
        return value == null ? null : value.getClass().getSimpleName();
    }

    private static List<String> keys(Map<?, ?> source) {
        return source == null ? List.of() : source.keySet().stream().map(String::valueOf).sorted().toList();
    }

    private static Map<String, Object> sanitizeMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        JsonNode node = OBJECT_MAPPER.valueToTree(source);
        redact(node);
        return OBJECT_MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() { });
    }

    private static void redact(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitive(field.getKey())) {
                    object.put(field.getKey(), REDACTED);
                } else {
                    redact(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(AgentReplayCapture::redact);
        }
    }

    private static boolean sensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
    }

    private static String shape(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return "{}";
        }
        Map<String, String> shape = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            shape.put(entry.getKey(), sensitive(entry.getKey()) ? REDACTED
                : entry.getValue() == null ? "null" : entry.getValue().getClass().getSimpleName());
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(shape);
        } catch (Exception e) {
            return shape.toString();
        }
    }

    private static byte[] serializedBytes(Object value) {
        try {
            return value == null ? new byte[0] : OBJECT_MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            return bytes(String.valueOf(value));
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source == null ? new byte[0] : source);
            return hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    /** 一次 onActing 可能包含多个并行工具调用，按 toolCallId 分别聚合事件。 */
    public final class ToolBatchCapture {
        private final Map<String, ToolObservation> byId = new LinkedHashMap<>();
        private final List<ToolObservation> observations;
        private final AtomicBoolean completed = new AtomicBoolean();

        private ToolBatchCapture(List<ToolObservation> observations) {
            this.observations = List.copyOf(observations);
            for (ToolObservation observation : observations) {
                byId.put(observation.toolCallId, observation);
            }
        }

        public void onEvent(AgentEvent event) {
            if (event instanceof ToolResultTextDeltaEvent text) {
                ToolObservation observation = observation(text.getToolCallId());
                if (observation != null) {
                    observation.append(text.getDelta());
                }
            } else if (event instanceof ToolResultDataDeltaEvent data) {
                ToolObservation observation = observation(data.getToolCallId());
                if (observation != null) {
                    observation.append(serializedBytes(data.getData()));
                }
            } else if (event instanceof ToolResultEndEvent end) {
                ToolObservation observation = observation(end.getToolCallId());
                if (observation != null) {
                    observation.state = end.getState();
                }
            }
        }

        public void complete(String fallbackState, String errorMessage) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            for (ToolObservation observation : observations) {
                String state = observation.state == null ? fallbackState : observation.state.name();
                toolCalls.add(observation.toSnapshot(state, errorMessage));
            }
        }

        private ToolObservation observation(String toolCallId) {
            ToolObservation observation = byId.get(toolCallId);
            return observation == null && observations.size() == 1 ? observations.get(0) : observation;
        }
    }

    private static final class ToolObservation {
        private final int sequence;
        private final String toolCallId;
        private final String toolName;
        private final String kind;
        private final String inputShape;
        private final String inputSha256;
        private final MessageDigest resultDigest;
        private int resultChars;
        private ToolResultState state;

        private ToolObservation(int sequence, ToolUseBlock call, AgentCallKind kind) {
            this.sequence = sequence;
            this.toolCallId = call.getId();
            this.toolName = call.getName();
            this.kind = kind == null ? AgentCallKind.TOOL.name() : kind.name();
            Map<String, Object> sanitized = sanitizeMap(call.getInput());
            this.inputShape = shape(sanitized);
            this.inputSha256 = sha256(serializedBytes(sanitized));
            try {
                this.resultDigest = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        private synchronized void append(String delta) {
            append(bytes(delta));
        }

        private synchronized void append(byte[] delta) {
            byte[] safe = delta == null ? new byte[0] : delta;
            resultDigest.update(safe);
            resultChars += new String(safe, StandardCharsets.UTF_8).length();
        }

        private synchronized AgentReplaySnapshot.ToolCallSnapshot toSnapshot(
            String resultState, String errorMessage) {
            return new AgentReplaySnapshot.ToolCallSnapshot(sequence, toolCallId, toolName, kind,
                inputShape, inputSha256, resultState, resultChars, hex(resultDigest.digest()), errorMessage);
        }
    }
}
