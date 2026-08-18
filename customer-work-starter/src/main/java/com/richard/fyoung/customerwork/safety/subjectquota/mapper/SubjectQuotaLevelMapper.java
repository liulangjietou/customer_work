package com.richard.fyoung.customerwork.safety.subjectquota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.entity.SubjectQuotaLevelDO;

/**
 * 配额等级 Mapper：CRUD 走 {@link BaseMapper}，
 * {@link #selectFingerprint} 表达"等级表变没变"的单行聚合探测。
 * @author owlzhangfq@gmail.com
 */
public interface SubjectQuotaLevelMapper extends BaseMapper<SubjectQuotaLevelDO> {

    /** 等级表版本指纹（供快照刷新判断是否需要重新加载）。 */
    String selectFingerprint();
}
