package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.MybatisApprovalStore;
import com.richard.fyoung.customerwork.core.common.PageResult;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao;
import com.richard.fyoung.customerworkapp.dao.UserRefundApprovalDao;
import com.richard.fyoung.customerworkapp.dao.UserRefundApprovalDao.RefundApprovalView;
import java.util.Comparator;
import java.util.NoSuchElementException;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;

/** 将两个标准审批存储投影为用户办理记录，复用订单读取边界，不参与审批或资金执行。 */
@Service
public class UserRefundApprovalQueryService {
    private static final Comparator<ApprovalRequest> NEWEST_FIRST =
        Comparator.comparingLong(ApprovalRequest::getCreatedAtMs).reversed()
            .thenComparing(ApprovalRequest::getId, Comparator.reverseOrder());
    private final ApprovalStore store;
    private final UserRefundApprovalDao approvalDao;
    private final UserOrderDao orderDao;

    public UserRefundApprovalQueryService(ApprovalStore store, UserRefundApprovalDao approvalDao, UserOrderDao orderDao) {
        this.store = store;
        this.approvalDao = approvalDao;
        this.orderDao = orderDao;
    }

    /** 已认证入口提供用户和会话；分页范围由 Controller 一处校验。 */
    public PageResult<RefundApprovalView> findPage(String sessionId, String userId, int page, int size) {
        Class<?> storeType = AopUtils.getTargetClass(store);
        if (!approvalDao.isEnabled() || !orderDao.isEnabled()
            || (storeType != MybatisApprovalStore.class && storeType != InMemoryApprovalStore.class)) {
            throw new IllegalStateException("refund approval query is unavailable for the configured store");
        }
        if (!approvalDao.hasOwnedSession(sessionId, userId)) {
            throw new NoSuchElementException("session not found");
        }
        if (storeType == MybatisApprovalStore.class) {
            return approvalDao.findPage(sessionId, userId, page, size);
        }
        var visible = store.findAll().stream()
            .filter(request -> request.getType() == ApprovalType.REFUND && sessionId.equals(request.getSessionId()))
            .filter(request -> request.getOrderId() != null && orderDao.findById(userId, request.getOrderId())
                .filter(order -> userId.equals(order.userId())).isPresent())
            .sorted(NEWEST_FIRST).toList();
        long offset = ((long) page - 1) * size;
        var items = visible.stream().skip(offset).limit(size).map(UserRefundApprovalQueryService::view).toList();
        return new PageResult<>(visible.size(), items);
    }

    private static RefundApprovalView view(ApprovalRequest request) {
        return new RefundApprovalView(request.getId(), request.getOrderId(), request.getAmount(),
            request.getStatus().name(), request.getExecutionStatus().name(), request.getCreatedAtMs(), request.getDecidedAtMs());
    }
}
