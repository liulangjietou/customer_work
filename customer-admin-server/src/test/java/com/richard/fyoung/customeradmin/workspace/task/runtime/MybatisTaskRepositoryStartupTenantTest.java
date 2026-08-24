package com.richard.fyoung.customeradmin.workspace.task.runtime;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台任务租约维护的租户上下文测试。
 *
 * <p>心跳和接管扫描都跑在独立维护线程里，没有 Web 请求租户上下文；不显式跨租户会被拦截器
 * fail-closed，表现为租约静默不续期、宕机任务永远无法被其它 Pod 接管。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisTaskRepositoryStartupTenantTest {

    @Test
    void recoverExpiredTasks_shouldRunAcrossTenants() {
        AiAgentTaskMapper taskMapper = mock(AiAgentTaskMapper.class);
        boolean[] candidateScanCrossTenant = {false};
        boolean[] terminalScanCrossTenant = {false};
        when(taskMapper.selectExpiredReplayable(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            candidateScanCrossTenant[0] = InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId");
            return List.of();
        });
        when(taskMapper.failExpiredUnrecoverable(any(), anyInt(), anyString())).thenAnswer(invocation -> {
            terminalScanCrossTenant[0] = InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId");
            return 0;
        });

        MybatisTaskRepository repository = new MybatisTaskRepository(taskMapper, properties());
        repository.useExecutor(Executors.newSingleThreadExecutor());
        repository.setReplayExecutor((task, context) -> "unused");
        repository.recoverExpiredTasks();
        repository.shutdown();

        assertTrue(candidateScanCrossTenant[0], "可重放候选扫描必须显式跨租户");
        assertTrue(terminalScanCrossTenant[0], "不可恢复任务收敛扫描必须显式跨租户");
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"), "作用域退出后不得泄漏");
    }

    private AgentTaskExecutorProperties properties() {
        AgentTaskExecutorProperties properties = new AgentTaskExecutorProperties();
        properties.setOwnerId("pod-a");
        return properties;
    }
}
