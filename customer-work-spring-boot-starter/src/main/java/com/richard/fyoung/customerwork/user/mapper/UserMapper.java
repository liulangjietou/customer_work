package com.richard.fyoung.customerwork.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.user.entity.UserDO;

/**
 * 终端用户账户 Mapper：账户创建为纯 INSERT（无 upsert 语义，用户名唯一由表约束保证），
 * 故仅继承 {@link BaseMapper} 复用 {@code insert}/{@code selectById}/{@code selectOne}，无自定义 XML。
 * @author owlzhangfq@gmail.com
 */
public interface UserMapper extends BaseMapper<UserDO> {
}
