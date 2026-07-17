package com.richard.fyoung.customerwork.observability;

import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路追踪 Tracer 单测：span 包裹不改变原调用结果，且确实执行了被包裹的动作。
 * @author owlzhangfq@gmail.com
 */
class LoggingTracerTest {

    private final LoggingTracer tracer = new LoggingTracer();

    @Test
    void callTool_shouldPassThroughResult() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        Mono<ToolResultBlock> action = Mono.fromSupplier(() -> {
            invoked.set(true);
            return ToolResultBlock.text("已发货");
        });

        StepVerifier.create(tracer.callTool(null, null, () -> action))
            .assertNext(result -> assertTrue(
                result.getOutput().toString().contains("已发货")
                    || result.toString().contains("已发货"),
                "span 包裹不应改变工具结果"))
            .verifyComplete();

        assertTrue(invoked.get(), "被包裹的工具动作应被真正执行");
    }
}
