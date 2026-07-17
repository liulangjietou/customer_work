package com.richard.fyoung.customerwork.slotfilling.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 槽位收集进度持久化对象（贫血数据袋，仅承载 {@code cw_slot_filling_progress} 表的行映射）。
 *
 * <p>已收集槽位值以 JSON 字符串（{@code collected_json}）落库；序列化 / 反序列化在
 * {@code MybatisSlotFillingStore} 内完成，DO 不含任何逻辑。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_slot_filling_progress")
public class SlotFillingProgressDO {

    /** 收集进度键：sessionId:formName（应用赋值，非自增）。 */
    @TableId(value = "progress_key", type = IdType.INPUT)
    private String progressKey;

    /** 当前追问的槽位名。 */
    private String asking;

    /** 已收集槽位值（JSON）。 */
    private String collectedJson;
}
