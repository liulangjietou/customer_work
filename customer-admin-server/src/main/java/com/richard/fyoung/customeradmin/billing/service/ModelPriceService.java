package com.richard.fyoung.customeradmin.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.mapper.AiModelPriceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * token → 金额换算。
 *
 * <p>取价规则：同一 {@code (provider, model)} 下取<b>生效时间不晚于结算时刻的最新一条</b>。
 * 调价插新行而不改旧行，历史账单据此算得回去。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ModelPriceService {

    /** 单价按「元/百万 token」存，换算时除以这个基数。 */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** 金额保留 4 位小数：单次调用的费用常在厘以下，保留 2 位会把大量小额调用抹成 0。 */
    private static final int AMOUNT_SCALE = 4;

    private final AiModelPriceMapper priceMapper;

    public ModelPriceService(AiModelPriceMapper priceMapper) {
        this.priceMapper = priceMapper;
    }

    /**
     * 按 token 用量算金额。
     *
     * <p>缓存命中的 token 是 {@code inputTokens} 的子集，按缓存价单独计，
     * 剩下的部分才按输入价计——重复计一次会把账单虚高。</p>
     *
     * @return 金额（元）；查不到单价时返回 0 并打日志，不抛异常——
     *         计费算不出来不该阻断归集任务，缺价这件事由日志和"金额为 0"的异常报表暴露
     */
    public BigDecimal calculate(String provider, String modelName,
                                long inputTokens, long outputTokens, long cachedTokens,
                                LocalDateTime settleAt) {
        AiModelPrice price = findEffectivePrice(provider, modelName, settleAt);
        if (price == null) {
            log.error("model price not found, code={}, provider={}, model={}",
                "BILLING-PRICE-MISSING", provider, modelName);
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
        long cached = Math.max(0, Math.min(cachedTokens, inputTokens));
        long uncachedInput = Math.max(0, inputTokens - cached);

        BigDecimal amount = multiply(uncachedInput, price.getInputPrice())
            .add(multiply(outputTokens, price.getOutputPrice()))
            .add(multiply(cached, price.getCachedPrice()));
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 查生效单价：provider 为空时只按模型名匹配。
     *
     * <p>日志里没有 provider（{@code cw_agent_call_log} 只记 agent 信息），
     * 归集时传空是常态，因此这里必须容忍。</p>
     */
    public AiModelPrice findEffectivePrice(String provider, String modelName, LocalDateTime settleAt) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<AiModelPrice> wrapper = new LambdaQueryWrapper<AiModelPrice>()
            .eq(AiModelPrice::getModelName, modelName)
            .le(AiModelPrice::getEffectiveFrom, settleAt == null ? LocalDateTime.now() : settleAt)
            .orderByDesc(AiModelPrice::getEffectiveFrom);
        if (provider != null && !provider.isBlank()) {
            wrapper.eq(AiModelPrice::getProvider, provider);
        }
        List<AiModelPrice> candidates = priceMapper.selectList(wrapper);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private BigDecimal multiply(long tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens))
            .divide(MILLION, AMOUNT_SCALE + 2, RoundingMode.HALF_UP);
    }
}
