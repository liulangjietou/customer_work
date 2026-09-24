package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.agui.event.AguiEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AG-UI 文本消息的协议约束：先开始后内容、结束之后不再有内容、运行结束前每条都已结束。
 *
 * <p>往答复末尾追加说明的中间件（循环守卫、答复安全闸门）都要经受这道检查——框架适配器按回复标识拼消息，
 * 追加的位置一旦落在块结束之后，它会照发已结束消息的内容而不报任何错。</p>
 */
final class AguiTextMessageAssertions {

    private AguiTextMessageAssertions() {
    }

    static void assertWellFormedTextMessages(List<AguiEvent> events) {
        Set<String> started = new HashSet<>();
        Set<String> ended = new HashSet<>();
        for (AguiEvent event : events) {
            if (event instanceof AguiEvent.TextMessageStart start) {
                assertTrue(started.add(start.messageId()), "消息重复开始：" + start);
            } else if (event instanceof AguiEvent.TextMessageContent content) {
                assertTrue(started.contains(content.messageId()) && !ended.contains(content.messageId()),
                    "内容落在未开始或已结束的消息上：" + content);
            } else if (event instanceof AguiEvent.TextMessageEnd end) {
                assertTrue(ended.add(end.messageId()), "消息重复结束：" + end);
            } else if (event instanceof AguiEvent.RunFinished) {
                assertEquals(started, ended, "运行结束时仍有未结束的消息");
            }
        }
    }
}
