package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.service.ChatService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileContent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.WorkspaceFileNode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VibeCodingService} 单测：vibecoding 能力校验、会话隔离目录、产物清单 diff、
 * 目录树列举、文件内容读取。
 * @author owlzhangfq@gmail.com
 */
class VibeCodingServiceTest {

    private ChatService chatService;
    private AdminAgentInstanceFactory agentInstanceFactory;
    private AiAgentMapper agentMapper;
    private VibeCodingService service;

    /** agentCode=coder 的 agentRoot（等价于 data/admin-workspace/coder） */
    @TempDir
    Path agentRoot;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        agentInstanceFactory = mock(AdminAgentInstanceFactory.class);
        agentMapper = mock(AiAgentMapper.class);
        service = new VibeCodingService(chatService, agentInstanceFactory, agentMapper);

        // resolveWorkspace 返回 agentRoot（向后兼容，listChangedArtifacts 旧逻辑已不使用此方法）
        when(agentInstanceFactory.resolveWorkspace("coder")).thenReturn(agentRoot);
        // resolveSessionWorkspace 返回 agentRoot/sessions/{sessionId}
        when(agentInstanceFactory.resolveSessionWorkspace(anyString(), anyString())).thenAnswer(inv -> {
            String sessionId = inv.getArgument(1);
            Path p = agentRoot.resolve("sessions").resolve(sessionId);
            Files.createDirectories(p);
            return p;
        });
    }

    private AiAgent vibeCodingAgent() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("coder");
        agent.setCapabilities("chat,vibecoding");
        return agent;
    }

    // ===== 能力校验 =====

    @Test
    void stream_shouldRejectUnknownAgent() {
        when(agentMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.stream("coder", "s1", "写个脚本"));
    }

    @Test
    void stream_shouldRejectAgentWithoutVibeCodingCapability() {
        AiAgent chatOnly = new AiAgent();
        chatOnly.setAgentCode("coder");
        chatOnly.setCapabilities("chat");
        when(agentMapper.selectOne(any())).thenReturn(chatOnly);
        assertThrows(BizException.class, () -> service.stream("coder", "s1", "写个脚本"));
    }

    @Test
    void stream_shouldDelegateToChatService_whenCapable() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        // stream 会在用户消息头部注入路径指引，所以 chatService 收到的消息不是原始 "write 文件"
        // 而是含指引的 enrichedText，这里用 anyString() 匹配即可。
        when(chatService.chatStream(anyString(), anyString(), anyString()))
            .thenReturn(Flux.just(new ChatStreamChunk(ChatNodeKind.ANSWER, "好的")));

        List<ChatStreamChunk> emitted = service.stream("coder", "s1", "write 文件").collectList().block();

        assertEquals(List.of(new ChatStreamChunk(ChatNodeKind.ANSWER, "好的")), emitted);
    }

    @Test
    void stream_shouldInjectFileDirective_inEnrichedMessage() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        // 捕获实际传给 chatService 的消息内容
        java.util.concurrent.atomic.AtomicReference<String> capturedText = new java.util.concurrent.atomic.AtomicReference<>();
        when(chatService.chatStream(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            capturedText.set(inv.getArgument(2));
            return Flux.empty();
        });

        service.stream("coder", "sess-abc", "写一个 Hello World").blockLast();

        String enriched = capturedText.get();
        assertTrue(enriched.contains("sessions/sess-abc/"), "注入消息应包含会话目录路径");
        assertTrue(enriched.contains("write_file"), "注入消息应提示调用 write_file 工具");
        assertTrue(enriched.contains("写一个 Hello World"), "注入消息应保留原始用户输入");
    }

    // ===== 产物清单 diff（会话隔离目录）=====

    @Test
    void listChangedArtifacts_shouldDetectNewAndModifiedFiles_butNotUntouchedFiles() throws IOException {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(chatService.chatStream(anyString(), anyString(), anyString())).thenReturn(Flux.empty());

        // stream 开始前写两个文件（已存在于快照）
        Path sessionWs = agentRoot.resolve("sessions/s1");
        Files.createDirectories(sessionWs);
        Files.writeString(sessionWs.resolve("untouched.txt"), "same content throughout");
        Files.writeString(sessionWs.resolve("to-modify.txt"), "original");

        service.stream("coder", "s1", "写个脚本").blockLast();

        // 模拟 Agent 修改文件、新建文件
        Files.writeString(sessionWs.resolve("to-modify.txt"), "changed content, different size");
        Files.writeString(sessionWs.resolve("new-file.txt"), "brand new");

        List<String> changed = service.listChangedArtifacts("coder", "s1");

        assertTrue(changed.contains("new-file.txt"), "新建文件应被检测到");
        assertTrue(changed.contains("to-modify.txt"), "修改文件应被检测到");
        assertFalse(changed.contains("untouched.txt"), "未变更文件不应出现在清单");
    }

    @Test
    void listChangedArtifacts_differentSessions_shouldBeIsolated() throws IOException {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        when(chatService.chatStream(anyString(), anyString(), anyString())).thenReturn(Flux.empty());

        // 会话 s1 产出文件
        service.stream("coder", "s1", "任务A").blockLast();
        Path sessionWs1 = agentRoot.resolve("sessions/s1");
        Files.writeString(sessionWs1.resolve("output-a.java"), "class A{}");

        // 会话 s2 产出文件
        service.stream("coder", "s2", "任务B").blockLast();
        Path sessionWs2 = agentRoot.resolve("sessions/s2");
        Files.writeString(sessionWs2.resolve("output-b.java"), "class B{}");

        List<String> changedS1 = service.listChangedArtifacts("coder", "s1");
        List<String> changedS2 = service.listChangedArtifacts("coder", "s2");

        // s1 看不到 s2 的文件，反之亦然
        assertTrue(changedS1.contains("output-a.java"), "s1 应看到自己的产出物");
        assertFalse(changedS1.contains("output-b.java"), "s1 不应看到 s2 的产出物");
        assertTrue(changedS2.contains("output-b.java"), "s2 应看到自己的产出物");
        assertFalse(changedS2.contains("output-a.java"), "s2 不应看到 s1 的产出物");
    }

    @Test
    void listChangedArtifacts_shouldReturnEmpty_whenNoPriorStreamCallForSession() {
        when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
        List<String> changed = service.listChangedArtifacts("coder", "never-started-session");
        // 目录刚创建，空目录，快照 diff 为空
        assertEquals(List.of(), changed);
    }

    // ===== 目录树列举 =====

    @Nested
    class ListWorkspaceFiles {

        @Test
        void shouldReturnEmptyList_whenSessionWorkspaceIsEmpty() {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            List<WorkspaceFileNode> nodes = service.listWorkspaceFiles("coder", "empty-session");
            assertTrue(nodes.isEmpty());
        }

        @Test
        void shouldReturnFileTree_withDirectoriesFirst() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            Path sessionWs = agentRoot.resolve("sessions/tree-test");
            Files.createDirectories(sessionWs.resolve("src/main/java"));
            Files.writeString(sessionWs.resolve("src/main/java/Hello.java"), "class Hello{}");
            Files.writeString(sessionWs.resolve("README.md"), "# readme");

            List<WorkspaceFileNode> nodes = service.listWorkspaceFiles("coder", "tree-test");

            // 根节点：src 目录 + README.md 文件，目录排前面
            assertEquals(2, nodes.size());
            assertTrue(nodes.get(0).directory(), "目录 src 应排在第一");
            assertEquals("src", nodes.get(0).name());
            assertFalse(nodes.get(1).directory(), "README.md 是文件");
            assertEquals("README.md", nodes.get(1).name());

            // 验证嵌套路径
            WorkspaceFileNode javaFile = nodes.get(0)   // src
                .children().get(0)                       // main
                .children().get(0)                       // java
                .children().get(0);                      // Hello.java
            assertEquals("Hello.java", javaFile.name());
            assertEquals("src/main/java/Hello.java", javaFile.relativePath());
        }

        @Test
        void shouldRejectAgentWithoutCapability_onListFiles() {
            AiAgent chatOnly = new AiAgent();
            chatOnly.setCapabilities("chat");
            when(agentMapper.selectOne(any())).thenReturn(chatOnly);
            assertThrows(BizException.class, () -> service.listWorkspaceFiles("coder", "s1"));
        }
    }

    // ===== 文件内容读取 =====

    @Nested
    class ReadFileContent {

        @Test
        void shouldReturnFileContentAndCorrectLanguage() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            Path sessionWs = agentRoot.resolve("sessions/read-test");
            Files.createDirectories(sessionWs.resolve("src"));
            Files.writeString(sessionWs.resolve("src/Foo.java"), "public class Foo {}");

            WorkspaceFileContent result = service.readFileContent("coder", "read-test", "src/Foo.java");

            assertEquals("src/Foo.java", result.relativePath());
            assertEquals("java", result.language());
            assertEquals("public class Foo {}", result.content());
            assertFalse(result.truncated());
        }

        @Test
        void shouldThrow_whenFileNotFound() {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            assertThrows(BizException.class,
                () -> service.readFileContent("coder", "read-test", "nonexistent.java"));
        }

        @Test
        void shouldThrow_onPathTraversal() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            // 写一个 sessionId 外的文件
            Files.writeString(agentRoot.resolve("secret.txt"), "should not read");

            assertThrows(BizException.class,
                () -> service.readFileContent("coder", "read-test", "../../secret.txt"),
                "路径穿越应被拒绝");
        }

        @Test
        void shouldThrow_whenPathIsDirectory() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            Path sessionWs = agentRoot.resolve("sessions/dir-test");
            Files.createDirectories(sessionWs.resolve("src"));

            assertThrows(BizException.class,
                () -> service.readFileContent("coder", "dir-test", "src"),
                "目录路径应被拒绝");
        }

        @Test
        void shouldDetectLanguage_byExtension() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            Path sessionWs = agentRoot.resolve("sessions/lang-test");
            Files.createDirectories(sessionWs);
            Files.writeString(sessionWs.resolve("app.yml"), "server: 8080");
            Files.writeString(sessionWs.resolve("data.json"), "{}");
            Files.writeString(sessionWs.resolve("schema.sql"), "SELECT 1");
            Files.writeString(sessionWs.resolve("script.ts"), "const x = 1;");

            assertEquals("yaml",       service.readFileContent("coder", "lang-test", "app.yml").language());
            assertEquals("json",       service.readFileContent("coder", "lang-test", "data.json").language());
            assertEquals("sql",        service.readFileContent("coder", "lang-test", "schema.sql").language());
            assertEquals("typescript", service.readFileContent("coder", "lang-test", "script.ts").language());
        }
    }

    // ===== 文件内容保存 =====

    @Nested
    class SaveFileContent {

        @Test
        void shouldCreateNewFile_whenFileNotExists() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());

            service.saveFileContent("coder", "save-test", "src/NewFile.java", "class New {}");

            Path saved = agentRoot.resolve("sessions/save-test/src/NewFile.java");
            assertTrue(Files.exists(saved), "应创建新文件");
            assertEquals("class New {}", Files.readString(saved));
        }

        @Test
        void shouldOverwriteExistingFile() throws IOException {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            Path sessionWs = agentRoot.resolve("sessions/overwrite-test");
            Files.createDirectories(sessionWs);
            Files.writeString(sessionWs.resolve("Foo.java"), "class OldFoo {}");

            service.saveFileContent("coder", "overwrite-test", "Foo.java", "class NewFoo {}");

            assertEquals("class NewFoo {}", Files.readString(sessionWs.resolve("Foo.java")));
        }

        @Test
        void shouldThrow_onPathTraversal() {
            when(agentMapper.selectOne(any())).thenReturn(vibeCodingAgent());
            assertThrows(BizException.class,
                () -> service.saveFileContent("coder", "save-test", "../../evil.txt", "evil"),
                "路径穿越应被拖绝");
        }

        @Test
        void shouldThrow_whenAgentWithoutCapability() {
            AiAgent chatOnly = new AiAgent();
            chatOnly.setCapabilities("chat");
            when(agentMapper.selectOne(any())).thenReturn(chatOnly);
            assertThrows(BizException.class,
                () -> service.saveFileContent("coder", "s1", "Foo.java", "class Foo {}"));
        }
    }
}
