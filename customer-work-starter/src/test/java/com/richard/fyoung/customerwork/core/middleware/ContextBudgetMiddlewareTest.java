package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文预算裁剪测试。
 *
 * <p><b>守的是什么 bug</b>：主对话链路的 {@code ReActAgent} 此前没有任何上下文上限，
 * 而长期记忆召回与 RAG 注入都默认开着——长会话叠加多轮工具结果会一路涨到模型报错为止。
 * 唯一的收敛手段 {@code CompactionConfig} 属于 Harness，框架层面挂不到 ReActAgent 上，
 * 且 Harness 默认关闭、用户不走那条路。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ContextBudgetMiddlewareTest {

    private Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private Msg system(String text) {
        return Msg.builder().role(MsgRole.SYSTEM).name("system")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private Msg synthetic(String text) {
        return Msg.builder().role(MsgRole.USER).name("system")
            .content(TextBlock.builder().text(text).build())
            .metadata(Map.of(Msg.METADATA_SYNTHETIC, true))
            .build();
    }


    /** 携带 tool_use 的 assistant 消息。 */
    private Msg toolUse(String id, String toolName) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(new ToolUseBlock(id, toolName, Map.of())).build();
    }

    /** 携带 tool_result 的消息，与上面的 tool_use 通过 id 对应。 */
    private Msg toolResult(String id, String toolName, String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).name("assistant")
            .content(new ToolResultBlock(id, toolName, TextBlock.builder().text(text).build()))
            .build();
    }

    private boolean hasBlock(Msg msg, Class<?> type) {
        return msg.getContent() != null && msg.getContent().stream().anyMatch(type::isInstance);
    }

    private List<Msg> conversation(int count) {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            msgs.add(user("m" + i));
        }
        return msgs;
    }

    @Test
    @DisplayName("未超预算时原样返回同一个列表实例")
    void underBudgetReturnsSameList() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 10, 2);
        List<Msg> input = conversation(10);
        assertSame(input, mw.trim(input), "未超预算不该产生新列表");
    }

    /**
     * 关闭开关时整条链路不做任何改动。
     *
     * <p>走 {@code onReasoning} 而不是直接调 {@code trim}：开关判定在入口，
     * {@code trim} 是纯裁剪逻辑、本就不看开关。</p>
     */
    @Test
    @DisplayName("关闭时 onReasoning 原样透传输入")
    void disabledPassesInputThrough() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(false, 5, 2);
        ReasoningInput input = new ReasoningInput(conversation(100), null, null);
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        mw.onReasoning(null, null, input, in -> {
            seen.set(in);
            return Flux.empty();
        }).blockLast();

        assertSame(input, seen.get(), "关闭时下游应收到原始 input 实例，未经任何包装");
    }

    /** 开启且超预算时，下游收到的必须是裁剪后的新 input。 */
    @Test
    @DisplayName("开启且超预算时 onReasoning 下发裁剪后的输入")
    void enabledTrimsThroughOnReasoning() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 8, 2);
        ReasoningInput input = new ReasoningInput(conversation(40), null, null);
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();

        mw.onReasoning(null, null, input, in -> {
            seen.set(in);
            return Flux.empty();
        }).blockLast();

        assertEquals(8, seen.get().messages().size(), "下游应收到裁剪到预算内的消息列表");
    }

    /** 丢中间、保两头：最早的背景信息与最近的话题都要在。 */
    @Test
    @DisplayName("超预算时保留最早若干条与最近若干条，丢弃中间")
    void trimsMiddleKeepsBothEnds() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 10, 3);
        List<Msg> input = conversation(50);

        List<Msg> out = mw.trim(input);

        assertEquals(10, out.size(), "应裁剪到预算上限");
        assertEquals("m0", out.get(0).getTextContent(), "最早一条应保留");
        assertEquals("m1", out.get(1).getTextContent());
        assertEquals("m2", out.get(2).getTextContent());
        assertEquals("m49", out.get(out.size() - 1).getTextContent(), "最近一条应保留");
        assertTrue(out.stream().noneMatch(m -> "m25".equals(m.getTextContent())),
            "中间部分应被丢弃");
    }


    /**
     * 裁剪不得把 tool_use 与它的 tool_result 切散。
     *
     * <p><b>守的是什么 bug</b>：原实现按位置直接 subList 首尾切，完全不看消息内容。
     * 这里刻意把工具调用对放在切口两侧——tool_use 在尾段预算之外、tool_result 在之内，
     * 按位置切会送出一个孤立的 tool_result，多数厂商对此直接返 400。
     * 也就是说这个中间件一旦按 javadoc 建议在生产开启，长会话会从"上下文超限"变成"整轮请求失败"。</p>
     */
    @Test
    @DisplayName("裁剪不得切散 tool_use 与 tool_result")
    void toolCallPairsSurviveTrimming() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 10, 3);

        List<Msg> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            input.add(user("m" + i));
        }
        // 尾段预算为 10-3=7 条，从尾数第 7 条正好落在 tool_result 上，tool_use 在它之外
        input.add(toolUse("call-1", "queryOrder"));
        input.add(toolResult("call-1", "queryOrder", "订单已发货"));
        for (int i = 14; i < 20; i++) {
            input.add(user("m" + i));
        }

        List<Msg> out = mw.trim(input);

        boolean keptUse = out.stream().anyMatch(m -> hasBlock(m, ToolUseBlock.class));
        boolean keptResult = out.stream().anyMatch(m -> hasBlock(m, ToolResultBlock.class));
        assertEquals(keptUse, keptResult,
            "tool_use 与 tool_result 必须同去同留，出现孤立的一半会让厂商直接返 400");
        assertTrue(keptResult, "本场景下工具调用对落在尾段，应当被保留");

        int useIdx = -1;
        int resultIdx = -1;
        for (int i = 0; i < out.size(); i++) {
            if (hasBlock(out.get(i), ToolUseBlock.class)) {
                useIdx = i;
            }
            if (hasBlock(out.get(i), ToolResultBlock.class)) {
                resultIdx = i;
            }
        }
        assertEquals(useIdx + 1, resultIdx, "tool_result 必须紧跟在它的 tool_use 之后");
    }

    /**
     * 为保住配对，允许略微超出条数预算。
     *
     * <p>预算是防上下文膨胀的近似手段，配对完整性却是硬性协议约束——
     * 超一两条只是多花点 token，切散则是整轮请求失败。两者冲突时选后者。</p>
     */
    @Test
    @DisplayName("保住配对时允许略微超出条数预算")
    void pairingMayExceedBudgetSlightly() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 10, 3);

        List<Msg> input = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            input.add(user("m" + i));
        }
        input.add(toolUse("call-1", "queryOrder"));
        input.add(toolResult("call-1", "queryOrder", "订单已发货"));
        for (int i = 14; i < 20; i++) {
            input.add(user("m" + i));
        }

        List<Msg> out = mw.trim(input);

        assertTrue(out.size() >= 10, "至少要达到预算");
        assertTrue(out.size() <= 12, "超出幅度应限于保住配对所需，实际 " + out.size());
        assertTrue(out.size() < input.size(), "仍然要有实际裁剪效果");
    }

    /** 整段都是一个巨大的工具调用组时无从下手，原样返回而不是切出一个残缺序列。 */
    @Test
    @DisplayName("无法在不切散配对的前提下裁剪时原样返回")
    void unsplittableConversationReturnsOriginal() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 5, 2);

        List<Msg> input = new ArrayList<>();
        input.add(toolUse("call-1", "queryOrder"));
        for (int i = 0; i < 9; i++) {
            input.add(toolResult("call-1", "queryOrder", "分片" + i));
        }

        List<Msg> out = mw.trim(input);

        assertSame(input, out, "整段是一个不可拆分的组，应原样返回");
    }

    /** system 消息承载的是角色设定与规则，裁掉它模型会当场失忆。 */
    @Test
    @DisplayName("system 消息永远保留且不占预算")
    void systemMessagesAlwaysKept() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 6, 2);
        List<Msg> input = new ArrayList<>();
        input.add(system("你是客服助手"));
        input.addAll(conversation(40));

        List<Msg> out = mw.trim(input);

        assertEquals(MsgRole.SYSTEM, out.get(0).getRole(), "system 应仍在最前");
        assertEquals("你是客服助手", out.get(0).getTextContent());
        assertEquals(7, out.size(), "system 不占预算：1 条 system + 6 条预算内消息");
    }

    /** RAG 召回块与待办提醒每轮重建，裁掉等于让模型这一轮失去参考资料。 */
    @Test
    @DisplayName("合成的瞬态消息（RAG 召回等）不被裁剪")
    void syntheticMessagesArePinned() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 4, 1);
        List<Msg> input = new ArrayList<>(conversation(20));
        input.add(synthetic("检索到的知识块"));

        List<Msg> out = mw.trim(input);

        assertTrue(out.stream().anyMatch(m -> "检索到的知识块".equals(m.getTextContent())),
            "RAG 召回块被裁掉了，模型这一轮将失去参考资料");
        assertEquals(5, out.size(), "1 条合成消息 + 4 条预算内消息");
    }

    /** keepEarliest 不能大于等于总预算，否则最近的消息一条都留不下。 */
    @Test
    @DisplayName("keepEarliest 超过预算时被夹紧，保证最近消息不丢")
    void keepEarliestClampedBelowBudget() {
        ContextBudgetMiddleware mw = new ContextBudgetMiddleware(true, 5, 99);
        List<Msg> out = mw.trim(conversation(30));

        assertEquals(5, out.size());
        assertEquals("m29", out.get(out.size() - 1).getTextContent(),
            "最近一条必须保留，否则模型看不到用户当前在说什么");
    }
}
