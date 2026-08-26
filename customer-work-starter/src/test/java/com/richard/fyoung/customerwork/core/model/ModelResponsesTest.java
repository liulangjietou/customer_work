package com.richard.fyoung.customerwork.core.model;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ModelResponses} 的行为测试 + <b>不得再各写一份</b>的门禁。
 *
 * @author owlzhangfq@gmail.com
 */
class ModelResponsesTest {

    private static final List<String> MODULE_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/main/java",
        "customer-admin-server/src/main/java",
        "customer-work-app-server/src/main/java",
        "customer-channel/src/main/java");

    @Test
    @DisplayName("拼接全部非空文本块，跳过非文本内容")
    void textJoinsAllTextBlocks() {
        List<ChatResponse> responses = List.of(
            response("你好"),
            response(""),
            response("，世界"));
        assertEquals("你好，世界", ModelResponses.text(responses));
    }

    @Test
    @DisplayName("null 与空列表返回空串，由调用方决定这算不算错误")
    void textTreatsMissingResponsesAsEmpty() {
        assertEquals("", ModelResponses.text(null));
        assertEquals("", ModelResponses.text(List.of()));
    }

    @Test
    @DisplayName("从围栏与解释文字里截出最外层 JSON 对象")
    void extractJsonObjectStripsSurroundingNoise() {
        assertEquals("{\"intent\":\"consult\"}",
            ModelResponses.extractJsonObject("好的，结果如下：\n```json\n{\"intent\":\"consult\"}\n```"));
        // 取首个 { 到末个 }：嵌套对象要整段拿回来，不能在第一个 } 处截断
        assertEquals("{\"a\":{\"b\":1}}",
            ModelResponses.extractJsonObject("前言 {\"a\":{\"b\":1}} 后记"));
    }

    @Test
    @DisplayName("截不出 JSON 时返回 null 而不是空串：调用方要能区分「没有」与「空对象」")
    void extractJsonObjectReturnsNullWhenAbsent() {
        assertNull(ModelResponses.extractJsonObject(null));
        assertNull(ModelResponses.extractJsonObject("   "));
        assertNull(ModelResponses.extractJsonObject("模型拒绝回答"));
        assertNull(ModelResponses.extractJsonObject("}{"));
    }

    /**
     * 门禁：TextBlock 提取流水不得在 {@link ModelResponses} 之外重新出现。
     *
     * <p>它是框架 API 的适配层——AgentScope 升级动了 {@code getContent()} 或 {@code TextBlock}
     * 就要跟着改。此前它在 7 个文件里逐字重复，跨 starter 与 admin 两个模块。</p>
     */
    @Test
    @DisplayName("门禁：模型文本提取只能有一处实现")
    void textExtractionMustNotBeReimplemented() throws IOException {
        Pattern extraction = Pattern.compile("\\.filter\\(TextBlock\\.class::isInstance\\)");
        List<String> offenders = sources()
            .filter(p -> !p.getFileName().toString().equals("ModelResponses.java"))
            .filter(p -> extraction.matcher(read(p)).find())
            .map(p -> p.getFileName().toString())
            .sorted()
            .toList();

        if (!offenders.isEmpty()) {
            fail("模型文本提取只能走 ModelResponses.text(...)，以下文件又各写了一份："
                + offenders + "\n它是框架 API 的适配层，散开之后框架升级要改 N 处，"
                + "而漏掉的那处只有跑到才发现。");
        }
    }

    @Test
    @DisplayName("门禁：JSON 对象截取只能有一处实现")
    void jsonExtractionMustNotBeReimplemented() throws IOException {
        Pattern method = Pattern.compile("String\\s+extractJsonObject\\s*\\(");
        List<String> offenders = sources()
            .filter(p -> !p.getFileName().toString().equals("ModelResponses.java"))
            .filter(p -> method.matcher(read(p)).find())
            .map(p -> p.getFileName().toString())
            .sorted()
            .toList();

        if (!offenders.isEmpty()) {
            fail("extractJsonObject 只能走 ModelResponses，以下文件又各写了一份：" + offenders
                + "\n解析口径一旦漂移（项目踩过 Jackson readTree 尾部 token 的坑），"
                + "修好一处另外几处照旧。");
        }
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
            .content(List.of(TextBlock.builder().text(text).build()))
            .build();
    }

    private static Stream<Path> sources() throws IOException {
        List<Path> roots = MODULE_SOURCE_ROOTS.stream()
            .map(ModelResponsesTest::resolveModulePath)
            .filter(Files::exists)
            .toList();
        if (roots.isEmpty()) {
            fail("未定位到任何模块源码目录，门禁的路径解析可能已失效");
        }
        return roots.stream().flatMap(root -> {
            try {
                return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"));
            } catch (IOException e) {
                throw new IllegalStateException("遍历源码目录失败：" + root, e);
            }
        });
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取源文件失败：" + p, e);
        }
    }

    /** 兼容两种工作目录：仓库根（多模块构建）与模块目录（IDE 单模块跑测试）。 */
    private static Path resolveModulePath(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        return Files.exists(fromRepoRoot) ? fromRepoRoot : Paths.get("..").resolve(moduleRelative);
    }
}
