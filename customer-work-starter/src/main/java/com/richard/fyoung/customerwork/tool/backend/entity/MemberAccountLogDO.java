package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会员账户问题处理日志持久化对象（贫血数据袋，映射 {@code cw_member_account_log} 表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_member_account_log")
public class MemberAccountLogDO {

    /** 处理日志自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 账户问题描述。 */
    private String issue;

    /** 处置话术。 */
    private String handling;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;
}
