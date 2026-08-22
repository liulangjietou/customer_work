package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentMetricsAvailability;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.ModelExperimentMetricsRow;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 从客服端调用日志读取真实实验曝光，驱动展示与自动护栏。 */
@Component
public class CallLogModelExperimentMetricsProvider implements ModelExperimentMetricsProvider {

    private static final int RATE_SCALE = 7;

    private final AppAgentCallStatsGatewayProvider appGatewayProvider;

    public CallLogModelExperimentMetricsProvider(AppAgentCallStatsGatewayProvider appGatewayProvider) {
        this.appGatewayProvider = appGatewayProvider;
    }

    @Override
    public ModelExperimentMetricsSnapshot snapshot(AiModelExperiment experiment) {
        String tenantId = TenantContext.require();
        long startedAtMs = toEpochMillis(experiment.getStartedAt() == null
            ? experiment.getCreateTime() : experiment.getStartedAt());
        LocalDateTime endedAt = experiment.getExpiresAt() == null
            ? LocalDateTime.now() : min(LocalDateTime.now(), experiment.getExpiresAt());
        List<ModelExperimentMetricsRow> rows = appGatewayProvider.get().extMapper()
            .experimentMetrics(tenantId, experiment.getId(), experiment.getRevision(),
                startedAtMs, toEpochMillis(endedAt));

        ModelExperimentMetricsRow controlRow = row(rows, "CONTROL");
        ModelExperimentMetricsRow treatmentRow = row(rows, "TREATMENT");
        ModelExperimentMetricsSnapshot.Arm control = toArm(controlRow);
        ModelExperimentMetricsSnapshot.Arm treatment = toArm(treatmentRow);
        long samples = samples(controlRow) + samples(treatmentRow);
        long errors = errors(controlRow) + errors(treatmentRow);
        return new ModelExperimentMetricsSnapshot(
            ModelExperimentMetricsAvailability.READY,
            samples == 0L ? "运行时已接入，当前尚无实验曝光样本" : null,
            samples,
            rate(errors, samples),
            null,
            control,
            treatment,
            LocalDateTime.now());
    }

    private ModelExperimentMetricsSnapshot.Arm toArm(ModelExperimentMetricsRow row) {
        long samples = samples(row);
        return new ModelExperimentMetricsSnapshot.Arm(
            samples, rate(errors(row), samples), row == null ? null : row.getP95LatencyMs());
    }

    private ModelExperimentMetricsRow row(List<ModelExperimentMetricsRow> rows, String arm) {
        if (rows == null) {
            return null;
        }
        return rows.stream().filter(item -> arm.equals(item.getArm())).findFirst().orElse(null);
    }

    private long samples(ModelExperimentMetricsRow row) {
        return row == null || row.getSamples() == null ? 0L : row.getSamples();
    }

    private long errors(ModelExperimentMetricsRow row) {
        return row == null || row.getErrors() == null ? 0L : row.getErrors();
    }

    private BigDecimal rate(long errors, long samples) {
        if (samples == 0L) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
        }
        return BigDecimal.valueOf(errors).divide(BigDecimal.valueOf(samples), RATE_SCALE,
            RoundingMode.HALF_UP);
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }
}
