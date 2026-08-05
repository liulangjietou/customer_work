package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customeradmin.config.AdminModelFailoverProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * admin 薄壳职责单测：候选类型继承自下沉版、构造签名保持不变（{@code AdminAgentInstanceFactory} 零改动），
 * 且沿用父类默认的"流中途失败也切下一候选"语义。降级/熔断的完整语义由 starter 的同名测试覆盖。
 * @author owlzhangfq@gmail.com
 */
class FailoverModelTest {

    /** 可选"先吐一个分片再失败"的桩模型。 */
    private static final class StubModel implements Model {
        private final String name;
        private final boolean failAfterFirstChunk;

        StubModel(String name, boolean failAfterFirstChunk) {
            this.name = name;
            this.failAfterFirstChunk = failAfterFirstChunk;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ChatResponse response = new ChatResponse(name, List.of(), null, null, "stop");
            if (failAfterFirstChunk) {
                return Flux.concat(Flux.just(response), Flux.error(new RuntimeException("mid-stream-" + name)));
            }
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private ModelCircuitBreakerRegistry newRegistry() {
        AdminModelFailoverProperties props = new AdminModelFailoverProperties();
        props.setFailureThreshold(3);
        props.setOpenDurationSeconds(60);
        return new ModelCircuitBreakerRegistry(props);
    }

    @Test
    void shouldExtendSharedFailoverModel() {
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, new StubModel("p", false))), newRegistry());

        assertInstanceOf(com.richard.fyoung.customerwork.model.failover.FailoverModel.class, model);
        assertEquals("p", model.getModelName());
    }

    @Test
    void shouldKeepMidStreamFailover_forDynamicAgentRuntime() {
        StubModel primary = new StubModel("p", true);
        StubModel backup = new StubModel("b", false);
        FailoverModel model = new FailoverModel(
            List.of(new FailoverModel.Candidate(1L, primary), new FailoverModel.Candidate(2L, backup)),
            newRegistry());

        List<ChatResponse> out = model.stream(List.<Msg>of(), null, null).collectList().block();

        // 主已吐分片后失败仍切备：智能体运行时宁可重复也要拿到完整回答
        assertEquals(2, out.size());
        assertEquals("p", out.get(0).getId());
        assertEquals("b", out.get(1).getId());
    }
}
