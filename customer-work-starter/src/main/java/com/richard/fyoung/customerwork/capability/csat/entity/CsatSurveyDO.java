package com.richard.fyoung.customerwork.capability.csat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * CSAT 调查持久化对象（贫血数据袋）：与 {@code cw_csat_survey} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.csat.CsatSurvey}。
 * {@code score} 可空——空表示已邀请但用户没评，回收率的分母靠它区分。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_csat_survey")
public class CsatSurveyDO {

    /** 会话 ID（自然主键：一次会话只该有一次整体评价）。 */
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    private String scopeId;

    /** 评分 1-5；null 表示已邀请未评价。 */
    private Integer score;

    private String comment;
    private Long invitedAtMs;
    private Long submittedAtMs;
}
