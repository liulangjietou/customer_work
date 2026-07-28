package com.richard.fyoung.customerwork.security.ratelimit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.security.ratelimit.entity.RateLimitRuleEntity;
import com.richard.fyoung.customerwork.security.ratelimit.mapper.RateLimitRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 限流规则存储（生产实现：{@code security.rate-limit.store-mode=jdbc} 时装配）。
 *
 * <p>规则落 {@code cw_rate_limit_rule} 表，多实例共享、后台可维护。建表由统一 {@code SchemaInitializer}
 * 负责，本类只表达读写；DO ↔ 领域对象转换收敛在本层。异常一律 {@code catch(Exception)}
 * （HikariPool/MyBatis 初始化异常是 RuntimeException，不是 SQLException 子类）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisRateLimitRuleStore implements RateLimitRuleStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisRateLimitRuleStore.class);

    private final RateLimitRuleMapper mapper;

    public MybatisRateLimitRuleStore(RateLimitRuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<RateLimitRule> findAll() {
        try {
            return toDomainList(mapper.selectList(null));
        } catch (Exception e) {
            log.error("[MybatisRateLimitRuleStore] findAll failed, code={}", "RATELIMIT-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public Optional<List<RateLimitRule>> findEnabled() {
        try {
            QueryWrapper<RateLimitRuleEntity> wrapper = new QueryWrapper<RateLimitRuleEntity>()
                .eq("enabled", true).orderByAsc("priority");
            return Optional.of(toDomainList(mapper.selectList(wrapper)));
        } catch (Exception e) {
            // 读失败返回 empty：Provider 据此保留上次快照 / 回退全局兜底，绝不把"读不到"当成"限死"
            log.error("[MybatisRateLimitRuleStore] findEnabled failed, code={}", "RATELIMIT-STORE-FINDENABLED-FAIL", e);
            return Optional.empty();
        }
    }

    @Override
    public void save(RateLimitRule rule) {
        if (rule == null || rule.pathPrefix() == null) {
            return;
        }
        try {
            RateLimitRuleEntity row = toDO(rule);
            if (row.getId() == null) {
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        } catch (Exception e) {
            log.error("[MybatisRateLimitRuleStore] save failed, code={}, rule={}",
                "RATELIMIT-STORE-SAVE-FAIL", rule.name(), e);
            throw new IllegalStateException("failed to save rate limit rule: " + rule.name(), e);
        }
    }

    @Override
    public Optional<String> fingerprint() {
        try {
            return Optional.of(String.valueOf(mapper.selectFingerprint()));
        } catch (Exception e) {
            log.error("[MybatisRateLimitRuleStore] fingerprint failed, code={}", "RATELIMIT-STORE-FINGERPRINT-FAIL", e);
            return Optional.empty();
        }
    }

    private List<RateLimitRule> toDomainList(List<RateLimitRuleEntity> rows) {
        List<RateLimitRule> result = new ArrayList<>(rows.size());
        for (RateLimitRuleEntity row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    private RateLimitRule toDomain(RateLimitRuleEntity row) {
        return new RateLimitRule(
            row.getId(),
            row.getRuleName(),
            row.getPathPrefix(),
            RateLimitDimension.parse(row.getDimension()),
            row.getLimitCount() == null ? 0 : row.getLimitCount(),
            RateLimitAlgorithm.parse(row.getAlgorithm()),
            row.getWindowSeconds() == null ? 60 : row.getWindowSeconds(),
            row.getPriority() == null ? 0 : row.getPriority(),
            row.getEnabled() != null && row.getEnabled());
    }

    private RateLimitRuleEntity toDO(RateLimitRule rule) {
        RateLimitRuleEntity row = new RateLimitRuleEntity();
        row.setId(rule.id());
        row.setRuleName(rule.name());
        row.setPathPrefix(rule.pathPrefix());
        row.setDimension(rule.dimension().name());
        row.setLimitCount(rule.limitCount());
        row.setAlgorithm(rule.algorithm().name());
        row.setWindowSeconds(rule.windowSeconds());
        row.setPriority(rule.priority());
        row.setEnabled(rule.enabled());
        long now = System.currentTimeMillis();
        if (rule.id() == null) {
            row.setCreatedAtMs(now);
        }
        row.setUpdatedAtMs(now);
        return row;
    }
}
