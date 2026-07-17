package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 会员持久化对象（贫血数据袋，映射 {@code cw_member} 表）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_member")
public class MemberDO {

    /** 会员ID（对应用户ID，应用赋值，非自增）。 */
    @TableId(value = "member_id", type = IdType.INPUT)
    private String memberId;

    /** 会员等级。 */
    private String level;

    /** 当前积分。 */
    private int points;

    /** 本月底到期积分。 */
    private int pointsExpiring;

    /** 等级权益。 */
    private String benefits;

    /** 下一等级。 */
    private String nextLevel;

    /** 升级所需再消费金额。 */
    private BigDecimal upgradeGap;

    /** 注册手机号。 */
    private String phone;
}
