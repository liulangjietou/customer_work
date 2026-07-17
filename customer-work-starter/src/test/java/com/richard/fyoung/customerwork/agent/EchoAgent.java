package com.richard.fyoung.customerwork.agent;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 测试用最小 Agent：把收到的最后一条消息回显为 "[name] 处理: 文本"，
 * 用于离线（无模型）验证 Pipeline / MsgHub 编排逻辑。
 * @author owlzhangfq@gmail.com
 */
class EchoAgent extends AgentBase {

    EchoAgent(String name) {
        super(name);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> input) {
        String text = input.isEmpty() ? "" : input.get(input.size() - 1).getTextContent();
        return Mono.just(Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name(getName())
            .content(TextBlock.builder().text(getName() + " 处理: " + text).build())
            .build());
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... messages) {
        return Mono.just(Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name(getName())
            .content(TextBlock.builder().text("interrupted").build())
            .build());
    }
}
