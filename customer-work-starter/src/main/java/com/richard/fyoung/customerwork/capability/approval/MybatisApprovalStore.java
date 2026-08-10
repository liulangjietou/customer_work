package com.richard.fyoung.customerwork.capability.approval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.capability.approval.entity.ApprovalRequestDO;
import com.richard.fyoung.customerwork.capability.approval.mapper.ApprovalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 审批工单存储（生产实现：补齐 {@code human-approval.store-mode=jdbc} 的持久化落地）。
 *
 * <p>把审批工单结构化写入 {@code cw_approval} 表，保证应用重启 / 多实例部署下审批单不丢失——
 * 这对涉及资金的退款审批至关重要（进程内 {@link InMemoryApprovalStore} 重启即清空）。</p>
 *
 * <p>由 {@link ApprovalConfig} 按 {@code human-approval.store-mode=jdbc} 装配；建表与种子由
 * {@code SchemaInitializer} 统一负责。领域对象 {@link ApprovalRequest} 与持久化对象
 * {@link ApprovalRequestDO} 的转换在本类内完成（读回走 {@code reconstruct}，跳过状态机校验）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisApprovalStore.class);

    private final ApprovalMapper mapper;

    public MybatisApprovalStore(ApprovalMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(ApprovalRequest request) {
        if (request == null || request.getId() == null) {
            return;
        }
        try {
            mapper.upsert(toDO(request));
        } catch (Exception e) {
            // 捕获 Exception：连接池获取连接失败时抛出 HikariPool$PoolInitializationException
            // （RuntimeException），非 SQLException 子类，必须一并兜住
            log.error("[MybatisApprovalStore] save failed, errorCode={}, id={}",
                "APPROVAL-STORE-SAVE-FAIL", request.getId(), e);
            throw new IllegalStateException("failed to save approval: " + request.getId(), e);
        }
    }

    @Override
    public void update(ApprovalRequest request) {
        save(request);
    }

    @Override
    public Optional<ApprovalRequest> find(String id) {
        try {
            ApprovalRequestDO entity = mapper.selectById(id);
            return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
        } catch (Exception e) {
            log.error("[MybatisApprovalStore] find failed, errorCode={}, id={}", "APPROVAL-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public List<ApprovalRequest> findAll() {
        try {
            return toDomainList(mapper.selectList(null));
        } catch (Exception e) {
            log.error("[MybatisApprovalStore] findAll failed, errorCode={}", "APPROVAL-STORE-FINDALL-FAIL", e);
            return List.of();
        }
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        try {
            QueryWrapper<ApprovalRequestDO> wrapper = new QueryWrapper<>();
            wrapper.eq("status", status.name());
            return toDomainList(mapper.selectList(wrapper));
        } catch (Exception e) {
            log.error("[MybatisApprovalStore] findByStatus failed, errorCode={}, status={}",
                "APPROVAL-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    @Override
    public void delete(String id) {
        try {
            mapper.deleteById(id);
        } catch (Exception e) {
            log.error("[MybatisApprovalStore] delete failed, errorCode={}, id={}", "APPROVAL-STORE-DELETE-FAIL", id, e);
        }
    }

    private List<ApprovalRequest> toDomainList(List<ApprovalRequestDO> rows) {
        List<ApprovalRequest> result = new ArrayList<>();
        for (ApprovalRequestDO row : rows) {
            result.add(toDomain(row));
        }
        return result;
    }

    /** 领域对象 → 持久化对象（枚举以 name 落库）。 */
    private ApprovalRequestDO toDO(ApprovalRequest request) {
        ApprovalRequestDO entity = new ApprovalRequestDO();
        entity.setId(request.getId());
        entity.setType(request.getType().name());
        entity.setSessionId(request.getSessionId());
        entity.setOrderId(request.getOrderId());
        entity.setAmount(request.getAmount());
        entity.setReason(request.getReason());
        entity.setCreatedAtMs(request.getCreatedAtMs());
        entity.setStatus(request.getStatus().name());
        entity.setOperator(request.getOperator());
        entity.setDecisionNote(request.getDecisionNote());
        entity.setDecidedAtMs(request.getDecidedAtMs());
        entity.setExecutionStatus(request.getExecutionStatus().name());
        entity.setExecutionFailureReason(request.getExecutionFailureReason());
        entity.setExecutionAttempts(request.getExecutionAttempts());
        return entity;
    }

    /** 持久化对象 → 领域对象（走包级 reconstruct，跳过状态机校验，仅把已发生的决策读回内存）。 */
    private ApprovalRequest toDomain(ApprovalRequestDO entity) {
        return ApprovalRequest.reconstruct(
            entity.getId(),
            ApprovalType.valueOf(entity.getType()),
            entity.getSessionId(),
            entity.getOrderId(),
            entity.getAmount(),
            entity.getReason(),
            entity.getCreatedAtMs(),
            ApprovalStatus.valueOf(entity.getStatus()),
            entity.getOperator(),
            entity.getDecisionNote(),
            entity.getDecidedAtMs(),
            ExecutionStatus.valueOf(entity.getExecutionStatus()),
            entity.getExecutionFailureReason(),
            entity.getExecutionAttempts());
    }
}
