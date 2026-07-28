package com.richard.fyoung.customerwork.security.ratelimit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.security.ratelimit.entity.RateLimitRuleEntity;

/**
 * 限流规则 Mapper：继承 {@link BaseMapper} 复用单表 CRUD，
 * {@link #selectFingerprint} 表达"规则表变没变"的单行聚合探测。
 * @author owlzhangfq@gmail.com
 */
public interface RateLimitRuleMapper extends BaseMapper<RateLimitRuleEntity> {

    /** 规则表版本指纹：{@code 行数:最大更新时间戳}（含停用规则，理由同敏感词表）。 */
    String selectFingerprint();
}
