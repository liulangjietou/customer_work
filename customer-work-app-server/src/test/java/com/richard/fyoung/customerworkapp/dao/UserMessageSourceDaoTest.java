package com.richard.fyoung.customerworkapp.dao;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证独立客服数据源的真实 Spring 装配，避免宿主提供其他数据源时误读宿主库。 */
class UserMessageSourceDaoTest {
    @Test
    void readsOnlyCustomerWorkStorageWhenTheHostHasAnotherDataSource() throws SQLException {
        DataSource customerStorage = mock(DataSource.class);
        DataSource hostStorage = mock(DataSource.class);
        when(customerStorage.getConnection()).thenThrow(new SQLException("customer storage unavailable", "08001"));

        new ApplicationContextRunner().withUserConfiguration(UserMessageSourceDao.class)
            .withBean("customerWorkDataSource", DataSource.class, () -> customerStorage)
            .withBean("hostDataSource", DataSource.class, () -> hostStorage)
            .run(context -> {
                assertThatThrownBy(() -> context.getBean(UserMessageSourceDao.class)
                    .findOwnedSources("tenant-a", "U1", "session", "message"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
                verify(customerStorage).getConnection();
                verifyNoInteractions(hostStorage);
            });
    }

    @Test
    void missingCustomerStorageDoesNotFallBackToTheHostsDataSource() {
        DataSource hostStorage = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(UserMessageSourceDao.class)
            .withBean("hostDataSource", DataSource.class, () -> hostStorage)
            .run(context -> {
                assertThatThrownBy(() -> context.getBean(UserMessageSourceDao.class)
                    .findOwnedSources("tenant-a", "U1", "session", "message"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
                verifyNoInteractions(hostStorage);
            });
    }
}
