package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 取本轮「用户真实原话」——Jev 判定情绪与意图的依据必须是用户说的话，不能是别的东西。
 *
 * <p><b>为什么不能直接取最后一条 USER 消息</b>：知识库注入中间件会追加一条 USER 角色的召回块
 * （带 {@link Msg#METADATA_SYNTHETIC} 标记），VibeCoding 等链路送进 Agent 的也是「指令 + 提问」的拼接文本。
 * 拿这些去判情绪，判的是知识库文档的情绪。因此优先取 {@link AgentCallMeta#question()}（各链路装的都是
 * 用户提问原文），回退时跳过合成消息。</p>
 *
 * <p>解析结果记进 {@link RuntimeContext}（每次调用新建一份），同一轮后续的钩子直接取用。</p>
 */
public final class JevTurnText {

    private static final String CTX_KEY = "typesafe.turn.text";

    private JevTurnText() {
    }

    /** 解析并记住本轮用户原话；取不到返回 null。 */
    public static String resolve(RuntimeContext ctx, List<Msg> msgs) {
        String remembered = recall(ctx);
        if (remembered != null) {
            return remembered;
        }
        String text = fromMeta(ctx);
        if (text == null) {
            text = lastUserText(msgs);
        }
        if (text != null && ctx != null) {
            ctx.put(CTX_KEY, String.class, text);
        }
        return text;
    }

    /** 同一轮里先前解析过的用户原话；没有返回 null。 */
    public static String recall(RuntimeContext ctx) {
        return ctx == null ? null : ctx.get(CTX_KEY, String.class);
    }

    private static String fromMeta(RuntimeContext ctx) {
        AgentCallMeta meta = ctx == null ? null : ctx.get(AgentCallMeta.class);
        return meta != null && StringUtils.hasText(meta.question()) ? meta.question() : null;
    }

    private static String lastUserText(List<Msg> msgs) {
        if (CollectionUtils.isEmpty(msgs)) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER && !isSynthetic(msg)
                && StringUtils.hasText(msg.getTextContent())) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    private static boolean isSynthetic(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.get(Msg.METADATA_SYNTHETIC));
    }
}
