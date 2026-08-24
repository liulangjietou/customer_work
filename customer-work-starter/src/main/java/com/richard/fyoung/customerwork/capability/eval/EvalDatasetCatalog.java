package com.richard.fyoung.customerwork.capability.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测工作集目录：统一装载 classpath 种子，并与数据库增量合并。
 *
 * <p>Runner、管理后台的 CRUD/导出/版本创建都必须从这里取“当前有效数据集”，否则同一租户在页面上看到的
 * 用例与实际执行的用例可能不同。种子继续随代码发布，数据库只承载覆盖、停用与增量。</p>
 */
public class EvalDatasetCatalog {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetCatalog.class);
    private static final String INTENT_PATH = "eval/intent-eval-cases.json";
    private static final String QUALITY_PATH = "eval/quality-eval-cases.json";

    private final EvalCaseStore caseStore;
    private final ObjectMapper objectMapper;

    public EvalDatasetCatalog(EvalCaseStore caseStore) {
        this(caseStore, new ObjectMapper());
    }

    EvalDatasetCatalog(EvalCaseStore caseStore, ObjectMapper objectMapper) {
        this.caseStore = caseStore;
        this.objectMapper = objectMapper;
    }

    /** 当前真正会参与执行的完整数据集，顺序稳定且不含 disabled。 */
    public List<PersistedEvalCase> effective(EvalType type) {
        return EvalDatasetMerger.merge(loadSeeds(type), caseStore.findByType(type));
    }

    public List<EvalCase> intentCases() {
        return effective(EvalType.INTENT).stream().map(PersistedEvalCase::toIntentCase).toList();
    }

    public List<QualityEvalCase> qualityCases() {
        return effective(EvalType.QUALITY).stream().map(PersistedEvalCase::toQualityCase).toList();
    }

    /** 判断编号是否存在于种子或数据库工作集中；创建接口据此区分 POST 与 PUT。 */
    public boolean contains(EvalType type, String caseId) {
        return effective(type).stream().anyMatch(item -> item.caseId().equals(caseId));
    }

    private List<PersistedEvalCase> loadSeeds(EvalType type) {
        String path = type == EvalType.INTENT ? INTENT_PATH : QUALITY_PATH;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return type == EvalType.INTENT ? loadIntentSeeds(in) : loadQualitySeeds(in);
        } catch (Exception e) {
            log.error("load eval dataset failed, errorCode={}, evalType={}, path={}",
                EvalErrorCodes.LOAD_FAIL, type, path, e);
            throw new IllegalStateException("eval dataset not loadable: " + path, e);
        }
    }

    private List<PersistedEvalCase> loadIntentSeeds(InputStream in) throws Exception {
        EvalCase[] seeds = objectMapper.readValue(in, EvalCase[].class);
        List<PersistedEvalCase> result = new ArrayList<>(seeds.length);
        for (EvalCase seed : seeds) {
            result.add(new PersistedEvalCase(seed.id(), EvalType.INTENT, seed.input(),
                seed.expectedIntent(), seed.category(), EvalCaseSource.SEED, true, null, 0L));
        }
        return List.copyOf(result);
    }

    private List<PersistedEvalCase> loadQualitySeeds(InputStream in) throws Exception {
        QualityEvalCase[] seeds = objectMapper.readValue(in, QualityEvalCase[].class);
        List<PersistedEvalCase> result = new ArrayList<>(seeds.length);
        for (QualityEvalCase seed : seeds) {
            result.add(new PersistedEvalCase(seed.id(), EvalType.QUALITY, seed.input(),
                seed.expected(), seed.category(), EvalCaseSource.SEED, true, null, 0L));
        }
        return List.copyOf(result);
    }
}
