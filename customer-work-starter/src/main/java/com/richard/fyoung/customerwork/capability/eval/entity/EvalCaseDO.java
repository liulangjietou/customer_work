package com.richard.fyoung.customerwork.capability.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 评测用例持久化对象（贫血数据袋）：与 {@code cw_eval_case} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase}（record）。
 * 主键用自增 {@code id}，业务唯一键是 {@code (tenant_id, eval_type, case_id)}——
 * 用例编号是人给的、可能被修改，拿它当主键会让"改个编号"变成删了重建。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_eval_case")
public class EvalCaseDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String evalType;
    private String caseId;
    private String input;

    /** INTENT 存期望意图（可空=期望快车道不命中）；QUALITY 存期望要点。 */
    private String expected;

    private String category;
    private String source;

    /** 是否参与评测；置 0 可屏蔽同 ID 的种子用例。 */
    private Boolean enabled;

    /** 溯源引用：来自 badcase 时记 badcase ID。 */
    private String originRef;

    private Long createdAtMs;
}
