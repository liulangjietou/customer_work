package com.richard.fyoung.customeradmin.eval.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.dto.EvalCaseSaveRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetCaseDiff;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetDiffVO;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetImportRequest;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseSource;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseConflictException;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetCatalog;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetRelease;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReleaseConflictException;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotter;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.PersistedEvalCase;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 评测数据集工作区、命名版本、审核和 diff 的唯一后台编排入口。 */
@Service
public class EvalDatasetAdminService {

    private final EvalGatewayProvider gatewayProvider;
    private final ObjectMapper objectMapper;

    public EvalDatasetAdminService(EvalGatewayProvider gatewayProvider, ObjectMapper objectMapper) {
        this.gatewayProvider = gatewayProvider;
        this.objectMapper = objectMapper;
    }

    /** 当前真正会参与评测的工作集，包含种子与数据库覆盖后的最终结果。 */
    public List<PersistedEvalCase> listCases(EvalType type) {
        EvalGateway gateway = gatewayProvider.dataset();
        return new EvalDatasetCatalog(gateway.caseStore()).effective(type);
    }

    public PersistedEvalCase createCase(EvalType type, EvalCaseSaveRequest request) {
        EvalGateway gateway = gatewayProvider.dataset();
        String caseId = request.caseId().trim();
        if (new EvalDatasetCatalog(gateway.caseStore()).contains(type, caseId)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "评测用例已存在: " + caseId);
        }
        PersistedEvalCase evalCase = toCase(type, caseId, request, EvalCaseSource.MANUAL,
            System.currentTimeMillis());
        try {
            gateway.caseStore().create(evalCase);
        } catch (EvalCaseConflictException conflict) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "评测用例已存在: " + caseId);
        }
        return evalCase;
    }

    public PersistedEvalCase updateCase(EvalType type, String caseId, EvalCaseSaveRequest request) {
        String normalizedId = caseId.trim();
        if (!normalizedId.equals(request.caseId().trim())) {
            throw new BizException(ResultCode.PARAM_INVALID, "URL 与请求体的 caseId 不一致");
        }
        EvalGateway gateway = gatewayProvider.dataset();
        if (!new EvalDatasetCatalog(gateway.caseStore()).contains(type, normalizedId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "评测用例不存在: " + normalizedId);
        }
        long createdAt = gateway.caseStore().find(type, normalizedId)
            .map(PersistedEvalCase::createdAtMs).orElse(System.currentTimeMillis());
        PersistedEvalCase evalCase = toCase(type, normalizedId, request, EvalCaseSource.MANUAL, createdAt);
        gateway.caseStore().save(evalCase);
        return evalCase;
    }

    /** 只删除数据库覆盖；种子本身不可删，需要用 enabled=false 覆盖来保留治理痕迹。 */
    public void deleteCase(EvalType type, String caseId) {
        EvalGateway gateway = gatewayProvider.dataset();
        if (gateway.caseStore().find(type, caseId).isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "种子用例不能删除；请更新为 enabled=false 进行有痕停用: " + caseId);
        }
        gateway.caseStore().delete(type, caseId);
    }

    public List<PersistedEvalCase> importCases(EvalType type, EvalDatasetImportRequest request) {
        Map<String, EvalCaseSaveRequest> unique = new LinkedHashMap<>();
        for (EvalCaseSaveRequest item : request.cases()) {
            String caseId = item.caseId().trim();
            if (unique.putIfAbsent(caseId, item) != null) {
                throw new BizException(ResultCode.PARAM_INVALID, "导入批次存在重复 caseId: " + caseId);
            }
        }
        EvalGateway gateway = gatewayProvider.dataset();
        long now = System.currentTimeMillis();
        List<PersistedEvalCase> cases = unique.entrySet().stream().map(entry -> {
            long createdAt = gateway.caseStore().find(type, entry.getKey())
                .map(PersistedEvalCase::createdAtMs).orElse(now);
            return toCase(type, entry.getKey(), entry.getValue(), EvalCaseSource.IMPORT, createdAt);
        }).toList();
        gateway.caseStore().saveAll(cases);
        return cases;
    }

    /** JSON 导出返回完整有效工作集；前端负责加文件名并触发下载。 */
    public List<PersistedEvalCase> exportCases(EvalType type) {
        return listCases(type);
    }

    public EvalDatasetRelease createVersion(EvalType type, String versionName) {
        String normalizedName = versionName.trim();
        EvalGateway gateway = gatewayProvider.dataset();
        EvalDatasetCatalog catalog = new EvalDatasetCatalog(gateway.caseStore());
        List<?> executableCases = type == EvalType.INTENT
            ? catalog.intentCases() : catalog.qualityCases();
        if (executableCases.isEmpty()) {
            throw new BizException(ResultCode.PARAM_INVALID, "空数据集不能创建命名版本");
        }
        EvalDatasetSnapshot snapshot = new EvalDatasetSnapshotter(gateway.snapshotStore())
            .snapshot(type, executableCases);
        EvalDatasetRelease release = new EvalDatasetRelease(UUID.randomUUID().toString(), type,
            normalizedName, snapshot.versionId(), snapshot.contentHash(), snapshot.caseCount(),
            EvalDatasetReviewStatus.DRAFT, null, currentUserId(), null,
            System.currentTimeMillis(), null);
        try {
            gateway.releaseStore().create(release);
        } catch (DuplicateKeyException | EvalDatasetReleaseConflictException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE,
                "命名版本已存在: " + normalizedName);
        }
        return release;
    }

    public List<EvalDatasetRelease> listVersions(EvalType type) {
        return gatewayProvider.dataset().releaseStore().findByType(type);
    }

    public EvalDatasetRelease review(String releaseId, EvalDatasetReviewStatus decision, String comment) {
        if (decision != EvalDatasetReviewStatus.APPROVED
            && decision != EvalDatasetReviewStatus.REJECTED) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核结论必须为 APPROVED 或 REJECTED");
        }
        EvalGateway gateway = gatewayProvider.dataset();
        EvalDatasetRelease current = requireRelease(gateway, releaseId);
        Long reviewer = currentUserId();
        if (reviewer != null && Objects.equals(current.createdBy(), reviewer)) {
            throw new BizException(ResultCode.FORBIDDEN, "创建人不能审核自己创建的数据集版本");
        }
        String normalizedComment = StringUtils.hasText(comment) ? comment.trim() : null;
        if (!gateway.releaseStore().review(releaseId, decision, normalizedComment,
            reviewer, System.currentTimeMillis())) {
            throw new BizException(ResultCode.PARAM_INVALID, "版本已审核或状态已变化，请刷新后重试");
        }
        return requireRelease(gateway, releaseId);
    }

    public EvalDatasetDiffVO diff(String fromReleaseId, String toReleaseId) {
        EvalGateway gateway = gatewayProvider.dataset();
        EvalDatasetRelease from = requireRelease(gateway, fromReleaseId);
        EvalDatasetRelease to = requireRelease(gateway, toReleaseId);
        if (from.evalType() != to.evalType()) {
            throw new BizException(ResultCode.PARAM_INVALID, "不同评测类型的版本不能比较");
        }
        Map<String, JsonNode> before = snapshotCases(gateway, from.snapshotVersionId());
        Map<String, JsonNode> after = snapshotCases(gateway, to.snapshotVersionId());
        List<String> added = after.keySet().stream().filter(id -> !before.containsKey(id)).sorted().toList();
        List<String> removed = before.keySet().stream().filter(id -> !after.containsKey(id)).sorted().toList();
        List<EvalDatasetCaseDiff> changed = before.entrySet().stream()
            .filter(entry -> after.containsKey(entry.getKey()))
            .filter(entry -> !entry.getValue().equals(after.get(entry.getKey())))
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new EvalDatasetCaseDiff(entry.getKey(), entry.getValue(), after.get(entry.getKey())))
            .toList();
        return new EvalDatasetDiffVO(fromReleaseId, toReleaseId, added, removed, changed);
    }

    /** 在线实验只允许绑定审核通过的 QUALITY 命名版本。 */
    public EvalDatasetRelease requireApprovedQualityRelease(String releaseId) {
        EvalDatasetRelease release = requireRelease(gatewayProvider.dataset(), releaseId);
        if (release.evalType() != EvalType.QUALITY
            || release.status() != EvalDatasetReviewStatus.APPROVED) {
            throw new BizException(ResultCode.PARAM_INVALID, "评测必须绑定 APPROVED 的 QUALITY 数据集版本");
        }
        return release;
    }

    /** 知识回归只接受审核版本中的实际目标，工作集中同名用例不构成版本内参评证据。 */
    public EvalDatasetSnapshot requireApprovedQualityCase(String releaseId, String targetCaseId) {
        EvalDatasetRelease release = requireApprovedQualityRelease(releaseId);
        EvalDatasetSnapshot snapshot = requireSnapshot(release.snapshotVersionId());
        if (snapshot.evalType() != EvalType.QUALITY || !snapshot.isContentIntact()
            || !Objects.equals(release.contentHash(), snapshot.contentHash())
            || release.caseCount() != snapshot.caseCount()) {
            throw new BizException(ResultCode.PARAM_INVALID, "审核版本与评测数据集快照不一致");
        }
        Map<String, JsonNode> cases = snapshotCases(snapshot);
        if (cases.isEmpty() || cases.size() != snapshot.caseCount() || !cases.containsKey(targetCaseId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "目标回归用例必须进入所选审核版本");
        }
        for (JsonNode item : cases.values()) {
            if (!item.path("input").isTextual() || !StringUtils.hasText(item.path("input").asText())
                || !item.path("expected").isTextual() || !StringUtils.hasText(item.path("expected").asText())) {
                throw new BizException(ResultCode.PARAM_INVALID, "质量回归用例缺少问题或期望要点");
            }
        }
        return snapshot;
    }

    public EvalDatasetSnapshot requireSnapshot(String versionId) {
        return gatewayProvider.dataset().snapshotStore().find(versionId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "评测数据集快照不存在: " + versionId));
    }

    /** 按运行绑定的不可变快照核验目标实际参评，不使用可能已被编辑或停用的当前工作集。 */
    public void requireExecutedCase(EvalRun run, EvalType expectedType, String caseId) {
        if (run.evalType() != expectedType || !StringUtils.hasText(run.versionBinding().datasetVersion())) {
            throw new BizException(ResultCode.PARAM_INVALID, "评测类型不一致或缺少实际数据集版本");
        }
        EvalDatasetSnapshot snapshot = requireSnapshot(run.versionBinding().datasetVersion());
        if (snapshot.evalType() != expectedType || !snapshot.isContentIntact()
            || !Objects.equals(snapshot.contentHash(), run.versionBinding().datasetFingerprint())) {
            throw new BizException(ResultCode.PARAM_INVALID, "评测数据集快照类型或指纹不一致");
        }
        Map<String, JsonNode> cases = snapshotCases(snapshot);
        if (cases.size() != snapshot.caseCount() || run.datasetSize() != cases.size()
            || run.total() != cases.size() || run.passed() < 0
            || run.passed() + run.failedCaseIds().size() != run.total()
            || new HashSet<>(run.failedCaseIds()).size() != run.failedCaseIds().size()
            || !cases.keySet().containsAll(run.failedCaseIds())) {
            throw new BizException(ResultCode.PARAM_INVALID, "评测运行数量或结果与实际数据集不一致");
        }
        if (!cases.containsKey(caseId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "目标回归用例未进入本次评测：" + caseId);
        }
    }

    private PersistedEvalCase toCase(EvalType type, String caseId, EvalCaseSaveRequest request,
                                     EvalCaseSource source, long createdAt) {
        String expected = StringUtils.hasText(request.expected()) ? request.expected().trim() : null;
        if (type == EvalType.QUALITY && expected == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "QUALITY 用例必须填写期望要点");
        }
        return new PersistedEvalCase(caseId, type, request.input().trim(), expected,
            trimToNull(request.category()), source, request.enabled() == null || request.enabled(),
            trimToNull(request.originRef()), createdAt);
    }

    private EvalDatasetRelease requireRelease(EvalGateway gateway, String releaseId) {
        return gateway.releaseStore().find(releaseId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "评测数据集版本不存在: " + releaseId));
    }

    private Map<String, JsonNode> snapshotCases(EvalGateway gateway, String snapshotVersionId) {
        EvalDatasetSnapshot snapshot = gateway.snapshotStore().find(snapshotVersionId)
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "评测数据集快照不存在: " + snapshotVersionId));
        return snapshotCases(snapshot);
    }

    private Map<String, JsonNode> snapshotCases(EvalDatasetSnapshot snapshot) {
        try {
            JsonNode root = objectMapper.readTree(snapshot.casesJson());
            if (!root.isArray()) {
                throw new IllegalStateException("snapshot casesJson is not an array");
            }
            Map<String, JsonNode> cases = new LinkedHashMap<>();
            for (JsonNode item : root) {
                String id = item.path("id").asText(null);
                if (!StringUtils.hasText(id) || cases.putIfAbsent(id, item) != null) {
                    throw new IllegalStateException("snapshot contains missing or duplicate case id");
                }
            }
            return cases;
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "评测数据集快照损坏，无法核验: " + snapshot.versionId());
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }
}
