package com.richard.fyoung.customerwork.core.model.experiment;

import com.richard.fyoung.customerwork.core.model.routing.ModelRoutingContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContextThreadLocalAccessor;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * 用户/会话稳定分桶的双臂模型路由器。
 *
 * <p>配额 DEGRADE 的 FALLBACK 指令优先级最高，直接交回基线模型的备用链。普通请求按
 * experimentId/revision/salt/主体做 SHA-256 分桶；同一主体在同一实验修订中稳定命中同一臂。
 * 实验臂失败不跨臂重试，避免污染曝光与护栏指标。</p>
 */
public final class OnlineExperimentRoutingModel implements Model {

    private static final int BASIS_POINTS = 10000;

    private final OnlineExperimentSpec spec;
    private final Model baseline;
    private final Model control;
    private final Model treatment;

    public OnlineExperimentRoutingModel(OnlineExperimentSpec spec,
                                        Model baseline,
                                        Model control,
                                        Model treatment) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.control = Objects.requireNonNull(control, "control");
        this.treatment = Objects.requireNonNull(treatment, "treatment");
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(context -> {
            if (ModelRoutingContext.isFallbackPreferred(context)) {
                clearAssignment(context);
                return baseline.stream(messages, tools, options);
            }
            OnlineExperimentAssignment existing = currentAssignment(context);
            if (belongsToCurrentExperiment(existing)) {
                return selectedModel(existing).stream(messages, tools, options);
            }
            if (System.currentTimeMillis() >= spec.expiresAtEpochMs()) {
                clearAssignment(context);
                return baseline.stream(messages, tools, options);
            }
            OnlineExperimentAssignment assignment = assign(context);
            bindAssignment(context, assignment);
            return selectedModel(assignment).stream(messages, tools, options);
        });
    }

    /** 供 dry-run/单测复用同一稳定分桶语义；空主体明确进入 CONTROL。 */
    public OnlineExperimentAssignment assign(String stableSubjectKey) {
        Integer bucket = stableSubjectKey == null || stableSubjectKey.isBlank()
            ? null : bucket(stableSubjectKey);
        boolean treatmentSelected = bucket != null && bucket < spec.treatmentBps();
        OnlineExperimentSpec.Arm arm = treatmentSelected ? spec.treatment() : spec.control();
        return new OnlineExperimentAssignment(spec.experimentId(), spec.revision(), arm.name(),
            arm.deploymentId(), bucket);
    }

    public OnlineExperimentSpec spec() {
        return spec;
    }

    @Override
    public String getModelName() {
        return "experiment:" + spec.experimentId() + ":r" + spec.revision();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return baseline.supportsNativeStructuredOutput()
            && control.supportsNativeStructuredOutput()
            && treatment.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return baseline.supportsNativeStructuredOutputWithTools()
            && control.supportsNativeStructuredOutputWithTools()
            && treatment.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return Math.min(baseline.getContextWindowSize(),
            Math.min(control.getContextWindowSize(), treatment.getContextWindowSize()));
    }

    private OnlineExperimentAssignment assign(ContextView context) {
        return assign(stableSubjectKey(context));
    }

    private String stableSubjectKey(ContextView context) {
        Object rawSubject = context.getOrDefault(QuotaSubjectContextThreadLocalAccessor.KEY, null);
        if (rawSubject instanceof QuotaSubject subject
            && !QuotaSubject.UNKNOWN_ID.equals(subject.id())) {
            return subject.type().name() + ":" + subject.id();
        }
        Object rawRuntime = context.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
        if (rawRuntime instanceof RuntimeContext runtimeContext
            && runtimeContext.getSessionId() != null && !runtimeContext.getSessionId().isBlank()) {
            return "SESSION:" + runtimeContext.getSessionId();
        }
        return null;
    }

    private void bindAssignment(ContextView context, OnlineExperimentAssignment assignment) {
        Object rawRuntime = context.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
        if (rawRuntime instanceof RuntimeContext runtimeContext) {
            runtimeContext.put(OnlineExperimentAssignment.class, assignment);
        }
    }

    private OnlineExperimentAssignment currentAssignment(ContextView context) {
        Object rawRuntime = context.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
        return rawRuntime instanceof RuntimeContext runtimeContext
            ? runtimeContext.get(OnlineExperimentAssignment.class) : null;
    }

    private boolean belongsToCurrentExperiment(OnlineExperimentAssignment assignment) {
        return assignment != null
            && spec.experimentId().equals(assignment.experimentId())
            && spec.revision().equals(assignment.revision())
            && ("CONTROL".equals(assignment.arm()) || "TREATMENT".equals(assignment.arm()));
    }

    private Model selectedModel(OnlineExperimentAssignment assignment) {
        return "TREATMENT".equals(assignment.arm()) ? treatment : control;
    }

    /** 同一 RuntimeContext 可能承载多次模型调用；回到基线时必须移除旧曝光，避免护栏指标串臂。 */
    private void clearAssignment(ContextView context) {
        Object rawRuntime = context.getOrDefault(AgentBase.RUNTIME_CONTEXT_KEY, null);
        if (rawRuntime instanceof RuntimeContext runtimeContext) {
            runtimeContext.put(OnlineExperimentAssignment.class, null);
        }
    }

    private int bucket(String stableSubjectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = spec.experimentId() + "|" + spec.revision() + "|"
                + spec.assignmentSalt() + "|" + stableSubjectKey;
            long value = ByteBuffer.wrap(digest.digest(input.getBytes(StandardCharsets.UTF_8))).getLong();
            return Math.floorMod(value, BASIS_POINTS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
