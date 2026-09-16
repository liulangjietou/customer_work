package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 独立提交受理事实；所有读取和更新显式限定精确租户、登录人及草稿。 */
@Repository
public class AgentDraftTrialStore {
    private static final String DRAFT_OWNED = "CAST(tenant_id AS BINARY)=CAST(? AS BINARY) "
        + "AND owner_user_id=? AND draft_id=?";
    private static final String OWNED = DRAFT_OWNED + " AND id=?";
    private static final String UNKNOWN_ERROR = AgentDraftTrialError.TRIAL_RESULT_UNKNOWN.name();
    private final JdbcTemplate jdbc;

    public AgentDraftTrialStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 主键唯一性给出唯一执行权；只有确切重复键表示已受理，其余写入故障必须阻止调用模型。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accept(AgentDraftTrialRecord record) {
        AgentDraftTrialScope scope = record.scope();
        try {
            jdbc.update("INSERT INTO ai_agent_draft_trial(id,tenant_id,owner_user_id,draft_id,draft_version,"
                    + "`input`,request_fingerprint,configuration_fingerprint,frozen_configuration,phase,"
                    + "accepted_at_ms,deadline_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.trialId(), scope.tenantId(), scope.ownerId(), scope.draftId(), record.draftVersion(),
                record.input(), record.requestFingerprint(), record.configurationFingerprint(),
                record.frozenConfiguration(), AgentDraftTrialPhase.RUNNING.name(), record.acceptedAtMs(),
                record.deadlineAtMs());
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 恢复只读取原记录；不可见标识与不存在标识均返回空。 */
    public Optional<AgentDraftTrialRecord> find(AgentDraftTrialScope scope) {
        return jdbc.query("SELECT * FROM ai_agent_draft_trial WHERE " + OWNED,
            (row, index) -> read(row), scope.tenantId(), scope.ownerId(), scope.draftId(), scope.trialId())
            .stream().findFirst();
    }

    /** 查询当前个人草稿的最近记录，不返回其它草稿或完整资源快照。 */
    public List<AgentDraftTrialSummary> list(String tenantId, long ownerId, String draftId) {
        return jdbc.query("SELECT id,draft_version,LEFT(`input`,160) AS input_excerpt,"
                + "configuration_fingerprint,phase,error_code,accepted_at_ms,finished_at_ms "
                + "FROM ai_agent_draft_trial WHERE " + DRAFT_OWNED
                + " ORDER BY accepted_at_ms DESC,id DESC LIMIT ?",
            (row, index) -> new AgentDraftTrialSummary(row.getString("id"), row.getLong("draft_version"),
                row.getString("input_excerpt"), row.getString("configuration_fingerprint"),
                AgentDraftTrialPhase.valueOf(row.getString("phase")), row.getString("error_code"),
                row.getLong("accepted_at_ms"), row.getObject("finished_at_ms", Long.class)),
            tenantId, ownerId, draftId, AgentDraftTrialLimits.HISTORY_LIMIT);
    }

    /** 只有仍有效的原受理记录可写成功；终态或截止时间之后的回调不能覆盖现状。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(AgentDraftTrialScope scope, String resultJson, long now) {
        return jdbc.update("UPDATE ai_agent_draft_trial SET phase=?,result_json=?,finished_at_ms=? WHERE "
                + OWNED + " AND phase=? AND deadline_at_ms>?",
            AgentDraftTrialPhase.SUCCEEDED.name(), resultJson, now, scope.tenantId(), scope.ownerId(),
            scope.draftId(), scope.trialId(), AgentDraftTrialPhase.RUNNING.name(), now) == 1;
    }

    /** 保存已知执行失败，错误码由服务层归类，异常原文和模型凭据不进入回执。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(AgentDraftTrialScope scope, String errorCode, long now) {
        // 已观察到取消/超时也可记录失败，但已有成功、失败或未知均保持不变。
        return jdbc.update("UPDATE ai_agent_draft_trial SET phase=?,error_code=?,finished_at_ms=? WHERE "
                + OWNED + " AND phase=?",
            AgentDraftTrialPhase.FAILED.name(), errorCode, now, scope.tenantId(), scope.ownerId(),
            scope.draftId(), scope.trialId(), AgentDraftTrialPhase.RUNNING.name()) == 1;
    }

    /** 无进程或终态写入失败时只收敛为未知，绝不重新受理或重放模型调用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(String tenantId, long ownerId, String draftId, long now) {
        jdbc.update("UPDATE ai_agent_draft_trial SET phase=?,error_code=?,finished_at_ms=? WHERE "
                + DRAFT_OWNED + " AND phase=? AND deadline_at_ms<=?",
            AgentDraftTrialPhase.UNKNOWN.name(), UNKNOWN_ERROR, now, tenantId, ownerId, draftId,
            AgentDraftTrialPhase.RUNNING.name(), now);
    }

    private AgentDraftTrialRecord read(ResultSet row) throws SQLException {
        var scope = new AgentDraftTrialScope(row.getString("tenant_id"), row.getLong("owner_user_id"),
            row.getString("draft_id"), row.getString("id"));
        return new AgentDraftTrialRecord(scope, row.getLong("draft_version"), row.getString("input"),
            row.getString("request_fingerprint"), row.getString("configuration_fingerprint"),
            row.getString("frozen_configuration"), AgentDraftTrialPhase.valueOf(row.getString("phase")),
            row.getString("result_json"), row.getString("error_code"), row.getLong("accepted_at_ms"),
            row.getLong("deadline_at_ms"), row.getObject("finished_at_ms", Long.class));
    }
}
