package com.richard.fyoung.customerwork.data.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 终端用户账户持久化对象（贫血数据袋）：与 {@code cw_user} 表一一映射。
 *
 * <p>充血实体见 {@link com.richard.fyoung.customerwork.data.user.UserAccount}（启停语义、密码哈希持有）。
 * {@code status} 以枚举名字符串落库，转换在 Store 层完成。{@code username} 唯一由表约束保证。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_user")
public class UserDO {

    /** 用户 ID（应用赋值，非自增）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 租户行归属；读取时回填权威编码，写入仍由 TenantLineInterceptor 统一约束。 */
    private String tenantId;

    private String username;
    private String passwordHash;
    private String nickname;
    private String phone;
    private String status;
    private Long createdAtMs;
    /** 头像访问 URL（相对路径，可为空）。 */
    private String avatarUrl;
    /** 配额等级编码（可为空 = 走配置默认档），见 cw_subject_quota_level.level_code。 */
    private String levelCode;
    /** 用户会话撤销版本。 */
    private Long sessionEpoch;
}
