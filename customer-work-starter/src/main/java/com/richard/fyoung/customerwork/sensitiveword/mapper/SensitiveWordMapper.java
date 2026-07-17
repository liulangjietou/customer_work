package com.richard.fyoung.customerwork.sensitiveword.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordEntity;

/**
 * 敏感词 Mapper：继承 {@link BaseMapper} 复用单表 CRUD；
 * {@link #upsert} 表达按 {@code word} 唯一键的 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordMapper extends BaseMapper<SensitiveWordEntity> {

    /** 按唯一键 word upsert：存在则更新类目/动作/启用/更新时间，不存在则插入。 */
    int upsert(SensitiveWordEntity record);
}
