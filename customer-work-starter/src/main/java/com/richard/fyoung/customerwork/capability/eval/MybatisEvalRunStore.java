package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.capability.eval.entity.EvalRunDO;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-Plus 评测运行记录存储（生产实现：{@code eval.store-mode=jdbc} 时装配）。
 *
 * <p>把每次评测的指标与失败明细写入 {@code cw_eval_run}，让"这版比上版好还是坏"有可查的历史。
 * 进程内实现重启即清空基线，评测就退化成一次性体检。</p>
 *
 * <p><b>与其他 Store 的失败处理差异</b>：{@link #save} 失败<b>抛异常</b>而不是只记日志——
 * 评测跑完却没落库等于整轮白跑（尤其是 QUALITY 类型，每次都有真实 token 成本），
 * 静默吞掉还会让下一次对比拿到错误的基线，得出反向结论。读操作则降级返回空，
 * 不因查不到历史而阻断新一轮评测。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisEvalRunStore implements EvalRunStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisEvalRunStore.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> METRIC_MAP = new TypeReference<>() { };

    private final EvalRunMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MybatisEvalRunStore(EvalRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(EvalRun run) {
        if (run == null || run.runId() == null) {
            return;
        }
        try {
            mapper.insert(toDO(run));
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] save failed, errorCode={}, runId={}",
                "EVAL-RUN-SAVE-FAIL", run.runId(), e);
            throw new IllegalStateException("failed to save eval run: " + run.runId(), e);
        }
    }

    @Override
    public Optional<EvalRun> find(String runId) {
        try {
            EvalRunDO row = mapper.selectById(runId);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] find failed, errorCode={}, runId={}",
                "EVAL-RUN-FIND-FAIL", runId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<EvalRun> findRecent(EvalType type, int limit) {
        try {
            List<EvalRunDO> rows = mapper.selectRecent(type.name(), limit);
            List<EvalRun> result = new ArrayList<>(rows.size());
            for (EvalRunDO row : rows) {
                result.add(toDomain(row));
            }
            return result;
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] findRecent failed, errorCode={}, evalType={}",
                "EVAL-RUN-RECENT-FAIL", type, e);
            return List.of();
        }
    }

    @Override
    public Optional<EvalRun> findBaseline(EvalType type, String runId) {
        try {
            EvalRunDO row = mapper.selectBaseline(type.name(), runId);
            return row == null ? Optional.empty() : Optional.of(toDomain(row));
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] findBaseline failed, errorCode={}, evalType={}",
                "EVAL-RUN-BASELINE-FAIL", type, e);
            return Optional.empty();
        }
    }

    private EvalRunDO toDO(EvalRun run) {
        EvalRunDO row = new EvalRunDO();
        row.setRunId(run.runId());
        row.setEvalType(run.evalType().name());
        row.setTotal(run.total());
        row.setPassed(run.passed());
        row.setPrimaryMetric(run.primaryMetric());
        row.setSecondaryMetric(run.secondaryMetric());
        row.setFailedCaseIdsJson(writeJson(run.failedCaseIds(), run.runId()));
        row.setFailuresJson(writeJson(run.failures(), run.runId()));
        row.setMetricsJson(writeJson(run.metrics(), run.runId()));
        row.setTriggerSource(run.trigger().name());
        row.setDatasetSize(run.datasetSize());
        EvalVersionBinding binding = run.versionBinding();
        row.setDatasetVersionId(binding == null ? null : binding.datasetVersion());
        row.setDatasetFingerprint(binding == null ? null : binding.datasetFingerprint());
        row.setVersionBindingJson(writeJson(binding, run.runId()));
        row.setPromptFingerprint(run.promptFingerprint());
        row.setRemark(run.remark());
        row.setCreatedAtMs(run.createdAtMs());
        return row;
    }

    private EvalRun toDomain(EvalRunDO row) {
        EvalVersionBinding binding = readVersionBinding(row);
        return new EvalRun(
            row.getRunId(),
            EvalType.valueOf(row.getEvalType()),
            row.getTotal() == null ? 0 : row.getTotal(),
            row.getPassed() == null ? 0 : row.getPassed(),
            row.getPrimaryMetric() == null ? 0.0d : row.getPrimaryMetric(),
            row.getSecondaryMetric() == null ? 0.0d : row.getSecondaryMetric(),
            readList(row.getFailedCaseIdsJson(), row.getRunId()),
            readList(row.getFailuresJson(), row.getRunId()),
            readMetrics(row.getMetricsJson(), row.getRunId()),
            EvalTrigger.valueOf(row.getTriggerSource()),
            row.getDatasetSize() == null ? 0 : row.getDatasetSize(),
            binding,
            row.getRemark(),
            row.getCreatedAtMs() == null ? 0L : row.getCreatedAtMs());
    }

    private EvalVersionBinding readVersionBinding(EvalRunDO row) {
        String json = row.getVersionBindingJson();
        if (json == null || json.isBlank()) {
            return EvalVersionBinding.legacy(row.getPromptFingerprint());
        }
        try {
            EvalVersionBinding binding = objectMapper.readValue(json, EvalVersionBinding.class);
            return binding == null ? EvalVersionBinding.legacy(row.getPromptFingerprint()) : binding;
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] deserialize version binding failed, errorCode={}, runId={}",
                "EVAL-RUN-DESERIALIZE-FAIL", row.getRunId(), e);
            return new EvalVersionBinding(row.getDatasetVersionId(), row.getDatasetFingerprint(),
                "", row.getPromptFingerprint(), "", "", "", "", "");
        }
    }

    private String writeJson(Object value, String runId) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] serialize failed, errorCode={}, runId={}",
                "EVAL-RUN-SERIALIZE-FAIL", runId, e);
            throw new IllegalStateException("failed to serialize eval run field: " + runId, e);
        }
    }

    /** 反序列化列表列；损坏的历史行不该让整个列表查询挂掉，故降级为空列表。 */
    private List<String> readList(String json, String runId) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] deserialize list failed, errorCode={}, runId={}",
                "EVAL-RUN-DESERIALIZE-FAIL", runId, e);
            return List.of();
        }
    }

    /** 反序列化指标列；同上，损坏行降级为空字典。 */
    private Map<String, Object> readMetrics(String json, String runId) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METRIC_MAP);
        } catch (Exception e) {
            log.error("[MybatisEvalRunStore] deserialize metrics failed, errorCode={}, runId={}",
                "EVAL-RUN-DESERIALIZE-FAIL", runId, e);
            return Map.of();
        }
    }
}
