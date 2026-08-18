package com.richard.fyoung.customeradmin.subjectquota.jdbc;

import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaHitMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaLevelMapper;

/**
 * 客服端库上的主体配额数据门面。
 *
 * <p>三张表都在客服端库（运行时要读它们来拦请求），后台经跨库门面维护——
 * 与内容风控三表、租户配额同一套路。用户表也在其中：等级绑定就落在 {@code cw_user.level_code} 上，
 * 后台改档必须写到同一个地方，另存一份映射表只会多出一个要对账的真源。</p>
 * @author owlzhangfq@gmail.com
 */
public record SubjectQuotaGateway(SubjectQuotaLevelMapper levelMapper,
                                  SubjectQuotaHitMapper hitMapper,
                                  UserMapper userMapper) {
}
