package com.richard.fyoung.customeradmin.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** 在业务事务提交后撤销 Sa-Token 会话；epoch 校验负责跨实例兜底。 */
@Service
public class SessionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final SysUserMapper userMapper;

    public SessionRevocationService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void revokeUserAfterCommit(Long userId) {
        runAfterCommitOrNow(() -> revokeUserNow(userId));
    }

    public void revokeTenantAfterCommit(String tenantId) {
        String capturedTenant = TenantContext.canonicalizeTenantId(tenantId);
        runAfterCommitOrNow(() -> revokeTenantNow(capturedTenant));
    }

    private void revokeUserNow(Long userId) {
        try {
            StpUtil.logout(userId);
            log.info("admin user sessions revoked, userId={}", userId);
        } catch (Exception e) {
            // 事务已经提交，不能把 Redis 故障伪装成数据库回滚；每请求 epoch 校验仍会拒绝旧会话。
            log.error("admin user session revocation failed, code={}, userId={}",
                "ADMIN-USER-SESSION-REVOKE-FAIL", userId, e);
        }
    }

    private void revokeTenantNow(String tenantId) {
        List<Long> userIds;
        try {
            userIds = TenantContext.callWith(tenantId, userMapper::selectUserIdsForSessionRevocation);
        } catch (Exception e) {
            log.error("admin tenant user lookup for revocation failed, code={}, tenantId={}",
                "ADMIN-TENANT-USER-LOOKUP-FAIL", tenantId, e);
            return;
        }
        int failed = 0;
        for (Long userId : userIds) {
            try {
                StpUtil.logout(userId);
            } catch (Exception e) {
                failed++;
                log.error("admin tenant user session revocation failed, code={}, tenantId={}, userId={}",
                    "ADMIN-TENANT-USER-SESSION-REVOKE-FAIL", tenantId, userId, e);
            }
        }
        log.info("admin tenant sessions revoked, tenantId={}, userCount={}, failedCount={}",
            tenantId, userIds.size(), failed);
    }

    private void runAfterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
