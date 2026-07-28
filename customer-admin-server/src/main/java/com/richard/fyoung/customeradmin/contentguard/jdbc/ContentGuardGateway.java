package com.richard.fyoung.customeradmin.contentguard.jdbc;

import com.richard.fyoung.customerwork.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;

/**
 * 内容风控数据门面：把客服端库上的三套 Mapper 打包成一个对象传递。
 *
 * <p>写侧直接复用 starter 的 {@code BaseMapper}（同一张表、同一套列，没有理由重造）；
 * 读侧另配 admin 自己的 ext Mapper——后台要的分页、多条件筛选、聚合统计，都不是运行时链路的诉求，
 * 塞进 starter 只会让写入链路背上展示需求（照 {@code AgentCallStatsGateway} 的先例）。</p>
 * @author owlzhangfq@gmail.com
 */
public record ContentGuardGateway(SensitiveWordMapper wordMapper,
                                  SensitiveWordExtMapper wordExtMapper,
                                  RateLimitRuleMapper ruleMapper,
                                  SensitiveWordHitLogMapper hitLogMapper,
                                  SensitiveWordHitLogExtMapper hitLogExtMapper) {
}
