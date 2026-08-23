package com.richard.fyoung.customerwork.capability.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 评测运行记录持久化对象（贫血数据袋）：与 {@code cw_eval_run} 表一一映射。
 *
 * <p>领域快照见 {@link com.richard.fyoung.customerwork.capability.eval.EvalRun}（record）。
 * 三个 {@code *Json} 列存的是列表/字典的 JSON 文本，转换在 Store 层完成——
 * 各类型评测的原始指标字段不同，摊成列会让每加一类评测就改一次表结构。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_eval_run")
public class EvalRunDO {

    /** 运行 ID（应用赋值的 UUID，非自增）。 */
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    /**
     * 写入顺序号，由数据库自增生成，应用侧只读。
     *
     * <p>插入时保持 null——MyBatis-Plus 默认只写非空字段，交给数据库自增即可。
     * 取基线与列表排序都按它，不按 {@code createdAtMs}：评测跑得快，同毫秒内的多次运行
     * 用时间戳分不出先后。</p>
     */
    private Long seq;

    private String evalType;
    private Integer total;
    private Integer passed;
    private Double primaryMetric;
    private Double secondaryMetric;

    /** 失败用例 ID 的 JSON 数组，回归识别的依据。 */
    private String failedCaseIdsJson;

    /** 失败明细的 JSON 数组（人读）。 */
    private String failuresJson;

    /** 该类型完整原始指标的 JSON 字典。 */
    private String metricsJson;

    private String triggerSource;
    private Integer datasetSize;

    /** 本次实际执行的数据集不可变版本。 */
    private String datasetVersionId;
    private String datasetFingerprint;

    /** 模型/提示词/Agent/知识/工具/Judge/rubric 的完整版本绑定 JSON。 */
    private String versionBindingJson;

    /** 本次运行时生效的提示词指纹——效果归因用：指标掉了，先看这一位变没变。 */
    private String promptFingerprint;

    private String remark;
    private Long createdAtMs;
}
