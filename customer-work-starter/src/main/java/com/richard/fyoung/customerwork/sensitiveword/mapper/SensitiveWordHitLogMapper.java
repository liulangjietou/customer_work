package com.richard.fyoung.customerwork.sensitiveword.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordHitLogEntity;

/**
 * 敏感词命中日志 Mapper：只需 {@link BaseMapper} 的单表写入与按条件查询。
 *
 * <p>后台看板要的分页/聚合读 SQL 不在这里——admin 侧有自己的读侧 Mapper（读写两侧诉求不同，
 * 照 {@code AgentCallStatsExtMapper} 的先例分开，不把展示需求塞进 starter 的写入链路）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordHitLogMapper extends BaseMapper<SensitiveWordHitLogEntity> {
}
