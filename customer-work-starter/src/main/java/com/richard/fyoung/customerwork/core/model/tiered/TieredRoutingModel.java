package com.richard.fyoung.customerwork.core.model.tiered;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按难度分级路由的模型装饰器：简单问题走便宜模型，其余走主模型。
 *
 * <p>与 {@link com.richard.fyoung.customerwork.core.model.failover.FailoverModel} 是两件事：
 * 那个回答"主模型挂了用谁"，本类回答"这个问题值得用多贵的模型"。此前系统只有前者——
 * 一句"运费怎么算"和一场多轮投诉处理打的是同一个模型、花的是同一份钱。</p>
 *
 * <h3>两个刻意的保守取舍</h3>
 *
 * <p><b>能力取交集</b>：结构化输出支持性、上下文窗口都按两档中较弱的报。路由是动态的，
 * 调用方拿到的能力声明必须对<b>任何一档</b>都成立；按主模型报会让走经济档的那次请求当场崩掉。</p>
 *
 * <p><b>首分片之后不回退</b>：经济档失败时回退主模型，但仅限还没吐出任何分片时。
 * 已经上屏的文字后面再接一段主模型的完整回答，用户看到的是两段拼在一起的错乱内容——
 * 这与 {@code ModelConfig} 给 FailoverModel 设 {@code midStreamFailoverEnabled=false} 是同一个理由。</p>
 * @author owlzhangfq@gmail.com
 */
public class TieredRoutingModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(TieredRoutingModel.class);

    private static final String CODE_ECONOMY_FAIL = "MODEL-TIER-ECONOMY-FAIL";

    private final Model economy;
    private final Model standard;
    private final ModelTierPolicy policy;

    /** 各档命中计数：这个功能到底省了多少，全靠这两个数说话。 */
    private final AtomicLong economyCount = new AtomicLong();
    private final AtomicLong standardCount = new AtomicLong();

    public TieredRoutingModel(Model economy, Model standard, ModelTierPolicy policy) {
        if (economy == null || standard == null) {
            throw new IllegalArgumentException("TieredRoutingModel requires both economy and standard models");
        }
        this.economy = economy;
        this.standard = standard;
        this.policy = policy;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        if (policy.decide(messages) == ModelTier.STANDARD) {
            standardCount.incrementAndGet();
            return standard.stream(messages, tools, options);
        }
        economyCount.incrementAndGet();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return economy.stream(messages, tools, options)
            .doOnNext(response -> emitted.set(true))
            .onErrorResume(error -> {
                if (emitted.get()) {
                    // 已经上屏的内容后面再接主模型的完整回答，用户看到的是错乱的两段
                    return Flux.error(error);
                }
                log.error("economy tier failed before first chunk, falling back to standard, code={}",
                    CODE_ECONOMY_FAIL, error);
                return standard.stream(messages, tools, options);
            });
    }

    /** 对外报主模型名：调用方感知到的是"这套系统用的是什么模型"，档位是内部优化。 */
    @Override
    public String getModelName() {
        return standard.getModelName();
    }

    /** 取交集：任一档不支持就报不支持，否则走经济档那次会当场崩。 */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return economy.supportsNativeStructuredOutput() && standard.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return economy.supportsNativeStructuredOutputWithTools()
            && standard.supportsNativeStructuredOutputWithTools();
    }

    /** 取较小值：路由是动态的，按大的报会让走经济档时超窗。 */
    @Override
    public int getContextWindowSize() {
        return Math.min(economy.getContextWindowSize(), standard.getContextWindowSize());
    }

    /** 经济档累计命中次数（省下的调用数）。 */
    public long economyCount() {
        return economyCount.get();
    }

    /** 标准档累计命中次数。 */
    public long standardCount() {
        return standardCount.get();
    }
}
