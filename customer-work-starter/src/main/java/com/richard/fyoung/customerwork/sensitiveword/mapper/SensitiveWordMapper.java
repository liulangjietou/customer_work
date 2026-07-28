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

    /**
     * 词表版本指纹：{@code 行数:最大更新时间戳}。
     *
     * <p>取<b>全表</b>（含停用词）而非仅启用词——停用一条词会同时改动 {@code enabled} 与
     * {@code updated_at_ms}，删除一条词会改动行数，新增一条词的 {@code updated_at_ms} 必为当前时刻，
     * 三类变更都能被这两个量捕获。语义上偏保守（个别无关变更也会触发一次重建），但绝不漏检。</p>
     */
    String selectFingerprint();
}
