package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.capability.approval.ApprovalConfig;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.MybatisApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerworkapp.dao.UserOrderDao;
import com.richard.fyoung.customerworkapp.dao.UserRefundApprovalDao;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 可选数据源和审批 Store 的真实 Spring 装配边界，不让宿主库或自定义 Store 冒充客服审批事实源。 */
class UserRefundApprovalWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ApprovalConfig.class, UserOrderDao.class,
            UserRefundApprovalDao.class, UserRefundApprovalQueryService.class);

    @Test
    void defaultMemoryStoreCanStartWithoutDataSourceButReadsAreUnavailable() {
        runner.withBean(CustomerWorkProperties.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(InMemoryApprovalStore.class, context.getBean(ApprovalStore.class));
            assertFalse(context.getBean(UserRefundApprovalDao.class).isEnabled());
            assertThrows(IllegalStateException.class,
                () -> context.getBean(UserRefundApprovalQueryService.class).findPage("session", "user", 1, 10));
        });
    }

    @Test
    void unrelatedHostDataSourceCannotBeUsedForRefundProjection() {
        var hostDataSource = mock(DataSource.class);
        runner.withBean(CustomerWorkProperties.class)
            .withBean("hostDataSource", DataSource.class, () -> hostDataSource).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.getBean(UserRefundApprovalDao.class).isEnabled());
                verifyNoInteractions(hostDataSource);
            });
    }

    @Test
    void standardJdbcStoreUsesExplicitCustomerWorkDataSource() {
        var properties = new CustomerWorkProperties();
        properties.getHumanApproval().setStoreMode("jdbc");
        runner.withBean(CustomerWorkProperties.class, () -> properties)
            .withBean("customerWorkDataSource", DataSource.class, () -> mock(DataSource.class))
            .withBean(ApprovalMapper.class, () -> mock(ApprovalMapper.class)).run(context -> {
                assertNull(context.getStartupFailure());
                assertInstanceOf(MybatisApprovalStore.class, context.getBean(ApprovalStore.class));
                assertTrue(context.getBean(UserRefundApprovalDao.class).isEnabled());
            });
    }

    @Test
    void overriddenMemorySubclassIsNotTreatedAsTheStandardTenantSafeStore() {
        var dataSource = mock(DataSource.class);
        runner.withBean(CustomerWorkProperties.class)
            .withBean("customerWorkDataSource", DataSource.class, () -> dataSource)
            .withBean(ApprovalStore.class, CustomApprovalStore::new).run(context -> {
                assertNull(context.getStartupFailure());
                assertThrows(IllegalStateException.class,
                    () -> context.getBean(UserRefundApprovalQueryService.class).findPage("session", "user", 1, 10));
                verifyNoInteractions(dataSource);
            });
    }

    private static class CustomApprovalStore extends InMemoryApprovalStore { }
}
