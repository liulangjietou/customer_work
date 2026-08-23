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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OnlineExperimentRoutingModelTest {

    @Test
    void sameAuthenticatedSubject_shouldStayInSameArmAndBindExposure() {
        StubModel baseline = new StubModel("baseline", false);
        StubModel control = new StubModel("control", false);
        StubModel treatment = new StubModel("treatment", false);
        OnlineExperimentRoutingModel model = model(baseline, control, treatment);
        RuntimeContext runtime = RuntimeContext.builder().userId("tenant-a").sessionId("session-a").build();
        QuotaSubject subject = QuotaSubject.user("user-42");

        model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context
                .put(QuotaSubjectContextThreadLocalAccessor.KEY, subject)
                .put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();

        OnlineExperimentAssignment expected = model.assign("USER:user-42");
        OnlineExperimentAssignment actual = runtime.get(OnlineExperimentAssignment.class);
        assertEquals(expected, actual);
        assertNotNull(actual.bucket());
        assertEquals(1, "TREATMENT".equals(actual.arm()) ? treatment.calls.get() : control.calls.get());
    }

    @Test
    void sameAnonymousSession_shouldStayInSameArmAcrossRuntimeContexts() {
        StubModel baseline = new StubModel("baseline", false);
        StubModel control = new StubModel("control", false);
        StubModel treatment = new StubModel("treatment", false);
        OnlineExperimentRoutingModel model = model(baseline, control, treatment);
        RuntimeContext first = RuntimeContext.builder().userId("tenant-a").sessionId("session-42").build();
        RuntimeContext second = RuntimeContext.builder().userId("tenant-a").sessionId("session-42").build();

        invoke(model, first);
        invoke(model, second);

        OnlineExperimentAssignment expected = model.assign("SESSION:session-42");
        assertEquals(expected, first.get(OnlineExperimentAssignment.class));
        assertEquals(expected, second.get(OnlineExperimentAssignment.class));
        assertEquals(2, "TREATMENT".equals(expected.arm()) ? treatment.calls.get() : control.calls.get());
    }

    @Test
    void missingStableSubject_shouldUseControlAndRecordNullBucket() {
        StubModel control = new StubModel("control", false);
        OnlineExperimentRoutingModel model = model(
            new StubModel("baseline", false), control, new StubModel("treatment", false));
        RuntimeContext runtime = RuntimeContext.builder().userId("tenant-a").sessionId("").build();

        model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context.put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();

        assertEquals(1, control.calls.get());
        OnlineExperimentAssignment assignment = runtime.get(OnlineExperimentAssignment.class);
        assertEquals("CONTROL", assignment.arm());
        assertEquals(null, assignment.bucket());
    }

    @Test
    void quotaFallback_shouldBypassExperimentArmsAndClearStaleExposure() {
        StubModel baseline = new StubModel("fallback", false);
        StubModel control = new StubModel("control", false);
        StubModel treatment = new StubModel("treatment", false);
        OnlineExperimentRoutingModel model = model(baseline, control, treatment);
        RuntimeContext runtime = RuntimeContext.builder().userId("tenant-a").sessionId("session-a").build();
        runtime.put(OnlineExperimentAssignment.class,
            new OnlineExperimentAssignment(6L, 2, "TREATMENT", 99L, 1234));

        List<ChatResponse> responses = model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> ModelRoutingContext.preferFallback(context)
                .put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();

        assertEquals("fallback", responses.get(0).getId());
        assertEquals(1, baseline.calls.get());
        assertEquals(0, control.calls.get());
        assertEquals(0, treatment.calls.get());
        assertNull(runtime.get(OnlineExperimentAssignment.class));
    }

    @Test
    void hardDeadline_shouldImmediatelyReturnBaselineWithoutRecordingExposure() {
        StubModel baseline = new StubModel("baseline", false);
        StubModel control = new StubModel("control", false);
        StubModel treatment = new StubModel("treatment", false);
        OnlineExperimentSpec expired = new OnlineExperimentSpec(7L, 3, "salt-123", 5000,
            System.currentTimeMillis() - 1,
            new OnlineExperimentSpec.Arm("CONTROL", 11L),
            new OnlineExperimentSpec.Arm("TREATMENT", 12L));
        OnlineExperimentRoutingModel model =
            new OnlineExperimentRoutingModel(expired, baseline, control, treatment);
        RuntimeContext runtime = RuntimeContext.builder().userId("tenant-a").sessionId("session-a").build();
        runtime.put(OnlineExperimentAssignment.class,
            new OnlineExperimentAssignment(6L, 2, "CONTROL", 98L, 4321));

        List<ChatResponse> responses = model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context
                .put(QuotaSubjectContextThreadLocalAccessor.KEY, QuotaSubject.user("user-42"))
                .put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();

        assertEquals("baseline", responses.get(0).getId());
        assertEquals(1, baseline.calls.get());
        assertEquals(0, control.calls.get());
        assertEquals(0, treatment.calls.get());
        assertNull(runtime.get(OnlineExperimentAssignment.class));
    }

    @Test
    void requestStartedBeforeDeadline_shouldKeepItsArmAfterDeadline() {
        StubModel baseline = new StubModel("baseline", false);
        StubModel control = new StubModel("control", false);
        StubModel treatment = new StubModel("treatment", false);
        OnlineExperimentSpec expired = new OnlineExperimentSpec(7L, 3, "salt-123", 5000,
            System.currentTimeMillis() - 1,
            new OnlineExperimentSpec.Arm("CONTROL", 11L),
            new OnlineExperimentSpec.Arm("TREATMENT", 12L));
        OnlineExperimentRoutingModel model =
            new OnlineExperimentRoutingModel(expired, baseline, control, treatment);
        RuntimeContext runtime = RuntimeContext.builder().userId("tenant-a").sessionId("session-a").build();
        OnlineExperimentAssignment existing =
            new OnlineExperimentAssignment(7L, 3, "TREATMENT", 12L, 1234);
        runtime.put(OnlineExperimentAssignment.class, existing);

        model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context.put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();

        assertEquals(0, baseline.calls.get());
        assertEquals(0, control.calls.get());
        assertEquals(1, treatment.calls.get());
        assertEquals(existing, runtime.get(OnlineExperimentAssignment.class));
    }

    @Test
    void selectedArmFailure_shouldNotCrossContaminateOtherArm() {
        StubModel control = new StubModel("control", true);
        StubModel treatment = new StubModel("treatment", true);
        OnlineExperimentRoutingModel model = model(new StubModel("baseline", false), control, treatment);
        String subjectKey = findSubjectForArm(model, "TREATMENT");

        assertThrows(IllegalStateException.class, () -> model
            .stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context.put(QuotaSubjectContextThreadLocalAccessor.KEY,
                QuotaSubject.user(subjectKey.substring("USER:".length()))))
            .collectList().block());
        assertEquals(1, treatment.calls.get());
        assertEquals(0, control.calls.get());
    }

    private String findSubjectForArm(OnlineExperimentRoutingModel model, String arm) {
        for (int i = 0; i < 10000; i++) {
            String key = "USER:user-" + i;
            if (arm.equals(model.assign(key).arm())) {
                return key;
            }
        }
        throw new AssertionError("no subject found for arm " + arm);
    }

    private OnlineExperimentRoutingModel model(Model baseline, Model control, Model treatment) {
        OnlineExperimentSpec spec = new OnlineExperimentSpec(7L, 3, "salt-123", 5000,
            System.currentTimeMillis() + 60000,
            new OnlineExperimentSpec.Arm("CONTROL", 11L),
            new OnlineExperimentSpec.Arm("TREATMENT", 12L));
        return new OnlineExperimentRoutingModel(spec, baseline, control, treatment);
    }

    private void invoke(OnlineExperimentRoutingModel model, RuntimeContext runtime) {
        model.stream(List.of(), List.of(), GenerateOptions.builder().build())
            .contextWrite(context -> context.put(AgentBase.RUNTIME_CONTEXT_KEY, runtime))
            .collectList().block();
    }

    private static final class StubModel implements Model {
        private final String name;
        private final boolean fail;
        private final AtomicInteger calls = new AtomicInteger();

        private StubModel(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            return Flux.defer(() -> {
                calls.incrementAndGet();
                return fail
                    ? Flux.error(new IllegalStateException("experiment arm failed: " + name))
                    : Flux.just(new ChatResponse(name, List.of(), null, null, "stop"));
            });
        }

        @Override
        public String getModelName() {
            return name;
        }

        @Override
        public int getContextWindowSize() {
            return 8192;
        }
    }
}
