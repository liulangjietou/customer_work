package com.richard.fyoung.customerwork.dialog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 对话阶段持久化对象（贫血数据袋，仅承载 {@code cw_dialog_stage} 表的行映射）。
 *
 * <p>阶段以枚举 name（{@code stage} 列）落库，{@code DialogStage} ↔ String 的转换在
 * {@code MybatisDialogStageStore} 内完成。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_dialog_stage")
public class DialogStageDO {

    /** 会话 ID（应用赋值，非自增）。 */
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    /** 当前对话阶段枚举 name：GREETING/COLLECTING/PROCESSING/CONFIRMING/ESCALATED。 */
    private String stage;
}
