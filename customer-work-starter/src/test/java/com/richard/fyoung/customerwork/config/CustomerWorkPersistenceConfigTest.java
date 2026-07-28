package com.richard.fyoung.customerwork.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化环境装配测试（无需 MySQL）：验证 {@link CustomerWorkPersistenceConfig} 的独立
 * SqlSessionFactory / SqlSessionTemplate / Mapper 在全上下文中正常装配，且与宿主自带的
 * MyBatis 环境（假想的 hostSqlSessionFactory）互不冲突。
 *
 * <p>HikariCP 惰性建连、MybatisSqlSessionFactoryBean 构建不连库，故本测试可离线运行——同时也验证了
 * 全部 {@code customerwork/mapper/*.xml} 能被正确解析、所有 Mapper 能被 @MapperScan 精确绑定到本环境 template。</p>
 * @author owlzhangfq@gmail.com
 */
class CustomerWorkPersistenceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CustomerWorkPersistenceConfig.class)
        .withBean(CustomerWorkProperties.class);

    @Test
    void whenAnyJdbc_shouldWireIndependentPersistenceBeans() {
        runner.withPropertyValues("customer-work.ticket.store-mode=jdbc")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("customerWorkDataSource");
                assertThat(context).hasBean("customerWorkSqlSessionFactory");
                assertThat(context).hasBean("customerWorkSqlSessionTemplate");
                // Mapper 被 @MapperScan 扫描并绑定到本环境 template（bean 名为接口名首字母小写）
                assertThat(context).hasBean("ticketMapper");
                assertThat(context).hasBean("orderMapper");
            });
    }

    /**
     * 命中日志与词表是两个独立开关，允许"词表用内存种子、只把命中记录落库"这种组合——
     * 该键若没登记进 {@link PersistenceJdbcCondition}，这个组合会因为 Mapper 取不到而启动失败。
     */
    @Test
    void whenOnlyHitLogJdbc_shouldStillWirePersistenceBeans() {
        runner.withPropertyValues("customer-work.sensitive-word.hit-log.store-mode=jdbc")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("customerWorkSqlSessionTemplate");
                assertThat(context).hasBean("sensitiveWordHitLogMapper");
            });
    }

    /** 限流规则层同理：只开规则层、其余域全 memory 时也必须能激活持久化环境。 */
    @Test
    void whenOnlyRateLimitRuleJdbc_shouldStillWirePersistenceBeans() {
        runner.withPropertyValues("customer-work.security.rate-limit.store-mode=jdbc")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("customerWorkSqlSessionTemplate");
                assertThat(context).hasBean("rateLimitRuleMapper");
            });
    }

    @Test
    void whenAllMemory_shouldNotWireAnyPersistenceBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("customerWorkSqlSessionFactory");
            assertThat(context).doesNotHaveBean("customerWorkSqlSessionTemplate");
            assertThat(context).doesNotHaveBean("ticketMapper");
        });
    }

    @Test
    void whenHostHasOwnSqlSessionFactory_shouldCoexistWithoutConflict() {
        runner.withPropertyValues("customer-work.tool-backend.mode=jdbc")
            .withUserConfiguration(HostMybatisConfig.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                // 宿主自带 SqlSessionFactory 与本环境 SqlSessionFactory 共存，互不覆盖
                assertThat(context).hasBean("hostSqlSessionFactory");
                assertThat(context).hasBean("customerWorkSqlSessionFactory");
                // 本库 Mapper 仍绑定到本环境 template（不会误绑到宿主 factory）
                assertThat(context).hasBean("orderMapper");
            });
    }

    /** 模拟宿主自带的 MyBatis 环境：一个同类型的 SqlSessionFactory Bean（用 mock，避免连库）。 */
    @Configuration
    static class HostMybatisConfig {
        @Bean
        SqlSessionFactory hostSqlSessionFactory() {
            return Mockito.mock(SqlSessionFactory.class);
        }
    }
}
