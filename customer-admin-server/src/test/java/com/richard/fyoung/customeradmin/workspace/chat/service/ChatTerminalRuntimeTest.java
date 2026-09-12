package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatMessagePhase;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentWorkspaceManager;
import com.richard.fyoung.customeradmin.workspace.runtime.ToolSourceInfo;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customerwork.core.middleware.ModelCompletionMiddleware;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeInjectionMiddleware;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalCapture;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalProvider;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeRetrievalResult;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实 ReAct 调用和确定性模型验证保存顺序，不能仅由 mock AgentResult 推导框架行为。 */
class ChatTerminalRuntimeTest {
    @TempDir
    Path workspace;
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRuntime_shouldConfirmSavedResultBeforeTerminal(boolean harness) {
        var chunks = run(false, harness);
        var terminal = chunks.get(chunks.size() - 1).terminal();
        assertNotNull(terminal);
        assertEquals(ChatMessagePhase.FINAL, terminal.phase());
        assertTrue(terminal.historySaved());
        assertEquals("stop", terminal.finishReason());
        assertNotNull(terminal.turnId());
        assertNotNull(terminal.messageId());
        assertEquals(1, chunks.stream().filter(chunk -> chunk.kind() == ChatNodeKind.TERMINAL).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRuntime_shouldNotCompleteWhenStateSaveFails(boolean harness) {
        var chunks = run(true, harness);
        var terminal = chunks.get(chunks.size() - 1).terminal();
        assertNotNull(terminal);
        assertEquals(ChatMessagePhase.FAILED, terminal.phase());
        assertFalse(terminal.historySaved());
        assertTrue(chunks.stream().anyMatch(chunk -> "确定性回复".equals(chunk.text())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRuntimeCapturesActualRetrievalBeforeConfirmingSources(boolean harness) {
        var chunks = run(false, harness, true);
        var terminal = chunks.get(chunks.size() - 1).terminal();
        assertTrue(terminal.historySaved());
        assertEquals(Boolean.TRUE, terminal.knowledgeSourcesSaved(), "普通和 Harness 工作区都必须把本轮采集器接到保存确认");
    }

    private List<ChatStreamChunk> run(boolean failSave, boolean harness) {
        return run(failSave, harness, false);
    }

    private List<ChatStreamChunk> run(boolean failSave, boolean harness, boolean captureSources) {
        var stateStore = spy(new InMemoryAgentStateStore());
        if (failSave) {
            doThrow(new IllegalStateException("save failed")).when(stateStore)
                .saveIfVersion(anyString(), anyString(), anyString(), any(State.class), anyLong());
            doThrow(new IllegalStateException("save failed")).when(stateStore)
                .save(anyString(), anyString(), anyString(), any(State.class));
        }
        Model model = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder().id("reply-test")
                    .content(List.of(TextBlock.builder().text("确定性回复").build())).finishReason("stop").build());
            }

            @Override
            public String getModelName() {
                return "terminal-test-model";
            }
        };
        var builder = ReActAgent.builder().name("terminal-test").sysPrompt("执行测试")
            .model(model).toolkit(new Toolkit()).stateStore(stateStore)
            .middleware(new ModelCompletionMiddleware());
        if (captureSources) {
            KnowledgeRetrievalProvider provider = (agentCode, query) -> "检索瞬态正文";
            builder.middleware(new KnowledgeInjectionMiddleware(provider, "coder"));
        }
        ReActAgent agent = builder.build();
        Agent runtime = harness ? HarnessAgent.Builder.fromAgent(agent).stateStore(stateStore).workspace(workspace)
            .generateOptions(GenerateOptions.builder().build()).build() : agent;
        try {
            var cache = mock(AgentInstanceCache.class);
            var factory = mock(AdminAgentInstanceFactory.class);
            when(cache.getOrBuild("coder")).thenReturn(runtime);
            RuntimeContext context = RuntimeContext.builder().userId("scope-1").sessionId("session-1").build();
            if (captureSources) {
                context.put(AgentInvocationIdentity.class,
                    new AgentInvocationIdentity("tenant-a", QuotaSubjectType.ADMIN_USER, "42", true)
                        .forInvocation(AgentInvocationIdentity.CHANNEL_ADMIN, "session-1", "coder"));
            }
            when(factory.contextFor("coder", "session-1")).thenReturn(context);
            when(factory.toolSourceFor("coder")).thenReturn(ToolSourceInfo.EMPTY);
            var sources = mock(ChatKnowledgeSourcesService.class);
            if (captureSources) {
                when(sources.saveConfirmed(any(), anyString(), anyString())).thenAnswer(call -> {
                    RuntimeContext captured = call.getArgument(0);
                    var retrievals = captured.get(KnowledgeRetrievalCapture.class).snapshot();
                    assertEquals(1, retrievals.size());
                    assertEquals("coder", retrievals.get(0).agentCode());
                    assertEquals(KnowledgeRetrievalResult.Status.HIT, retrievals.get(0).status());
                    assertFalse(stateStore.get("scope-1", "session-1", "agent_state", AgentState.class)
                        .orElseThrow().toJson().contains("检索瞬态正文"));
                    return true;
                });
            }
            var service = new ChatService(cache, factory, mock(ChatHistoryCache.class),
                mock(AgentMemorySyncService.class), new ExecutionModeRegistry(), new PlanConfirmationService(),
                mock(ChatAttachmentService.class), null, null, null, new AgentWorkspaceManager(null),
                new ChatCompletionVerifier(stateStore, sources));
            var chunks = service.chatStream("coder", "session-1", "测试输入").collectList().block(Duration.ofSeconds(10));
            assertEquals(failSave ? ChatMessagePhase.FAILED : ChatMessagePhase.FINAL,
                chunks.get(chunks.size() - 1).terminal().phase(),
                () -> "chunks=" + chunks + "; saved=" + stateStore.get("scope-1", "session-1", "agent_state",
                    AgentState.class).map(AgentState::toJson));
            return chunks;
        } finally {
            if (runtime instanceof HarnessAgent harnessAgent) harnessAgent.close();
            agent.close();
        }
    }
}
