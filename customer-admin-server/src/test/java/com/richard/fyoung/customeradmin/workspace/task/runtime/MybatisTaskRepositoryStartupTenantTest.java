package com.richard.fyoung.customeradmin.workspace.task.runtime;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.workspace.task.entity.AiAgentTask;

import com.richard.fyoung.customeradmin.workspace.task.mapper.AiAgentTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 重启清理孤儿任务的租户上下文测试。
 *
 * <p>清理跑在 {@code @PostConstruct} 里——启动期没有租户上下文，不显式跨租户会被拦截器
 * fail-closed；而那段代码外面裹着 {@code catch(Exception)}，异常被吞掉后表现为
 * "清理静默不生效"，比直接报错更难发现。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisTaskRepositoryStartupTenantTest {

    /** LambdaUpdateWrapper 解析字段名要读 MyBatis-Plus 的表信息缓存，脱离容器时需手工初始化。 */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AiAgentTask.class);
    }

    @Test
    void cleanupOrphanTasks_shouldRunAcrossTenants() {
        AiAgentTaskMapper taskMapper = mock(AiAgentTaskMapper.class);
        boolean[] crossTenant = {false};
        when(taskMapper.update(isNull(), any())).thenAnswer(invocation -> {
            crossTenant[0] = InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId");
            return 0;
        });

        MybatisTaskRepository repository = new MybatisTaskRepository(taskMapper, properties());
        repository.init();

        assertTrue(crossTenant[0], "重启清理是跨租户运维扫描，必须显式跨租户，否则会被 fail-closed 静默吞掉");
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"), "作用域退出后不得泄漏");
    }

    private AgentTaskExecutorProperties properties() {
        AgentTaskExecutorProperties properties = new AgentTaskExecutorProperties();
        properties.setCleanupOrphansOnStartup(true);
        return properties;
    }
}
