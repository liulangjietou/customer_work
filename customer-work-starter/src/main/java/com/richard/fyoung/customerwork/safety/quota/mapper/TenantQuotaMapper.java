package com.richard.fyoung.customerwork.safety.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.safety.quota.entity.TenantQuotaDO;

/**
 * 租户配额 Mapper（starter 定义，admin 复用同一套做后台管理——照内容风控三表的先例）。
 * @author owlzhangfq@gmail.com
 */
public interface TenantQuotaMapper extends BaseMapper<TenantQuotaDO> {
}
