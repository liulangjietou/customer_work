package com.richard.fyoung.customerwork.routing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 坐席持久化对象（贫血数据袋）：与 {@code cw_seat_agent} 表一一映射。
 *
 * <p>{@code skills} 以逗号分隔的技能标签串落库（如 {@code refund,invoice}），拆解/拼接在
 * {@link com.richard.fyoung.customerwork.routing.MybatisSeatAgentStore} 完成；{@code online} 以 0/1 落库；
 * {@code seatGroup} 对应列 {@code seat_group}（避开 SQL 保留字 group）。驼峰字段由下划线映射自动对应下划线列。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_seat_agent")
public class SeatAgentDO {

    /** 坐席 ID（应用赋值，非自增）。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String name;
    /** 逗号分隔技能标签串。 */
    private String skills;
    private Integer maxLoad;
    private Integer currentLoad;
    /** 是否在线（1 在线 / 0 离线）。 */
    private Boolean online;
    /** 坐席分组（列名 seat_group，避开保留字）。 */
    private String seatGroup;
    private Long createdAtMs;
    private Long updatedAtMs;
}
