package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link HandoffService} 与 {@link HandoffConfig} 的 Spring 装配回归单测（特性「人机切换」）。
 *
 * <p>回归的 bug：{@link HandoffService} 同时存在无参与单参构造，单参构造此前<b>未标</b>
 * {@code @Autowired}——Spring 对"多构造器 + 存在无参 + 无 @Autowired"会回退无参构造，导致
 * HandoffService 永远 {@code new InMemoryHandoffStore()}、{@code human-handoff.store-mode=jdbc}
 * 空转、{@link HandoffConfig} 装配的 Store Bean 成孤儿。本测试<b>不</b>断言构造器注解本身，而是断言
 * 真实容器里 HandoffService 用的就是那个被装配的 Store Bean（行为等价、反注解实现细节）。</p>
 *
 * <p>验证手段：往容器里的 {@link HandoffStore} Bean 写一张工单，再经 HandoffService 读——只有二者共享
 * 同一实例（即单参构造被选中）才读得到。回退无参构造时 HandoffService 持有另一个 InMemory 实例 → 读不到。</p>
 * @author owlzhangfq@gmail.com
 */
class HandoffServiceWiringTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    /** memory 模式（默认）：Store Bean 是 InMemory，且 HandoffService 用的就是它（非自建实例）。 */
    @Test
    void memoryMode_handoffServiceShouldUseInjectedInMemoryStoreBean() {
        new ApplicationContextRunner()
            .withBean(CustomerWorkProperties.class)
            .withUserConfiguration(HandoffConfig.class)
            .withBean(HandoffService.class)
            .run(context -> {
                HandoffStore store = context.getBean(HandoffStore.class);
                assertInstanceOf(InMemoryHandoffStore.class, store,
                    "memory 模式（默认）应装配 InMemoryHandoffStore");

                HandoffService service = context.getBean(HandoffService.class);
                String id = "HO-wiring-mem-" + UUID.randomUUID();
                store.save(new HandoffTicket(id, "s1", "test", System.currentTimeMillis()));
                assertTrue(service.find(id).isPresent(),
                    "HandoffService 必须用注入的 Store Bean（回退无参构造则读不到该工单）");
            });
    }

    /** jdbc 模式：Store Bean 是 MybatisHandoffStore，HandoffService 经它把工单真正落库 cw_handoff_ticket。 */
    @Test
    void jdbcMode_handoffServiceShouldUseInjectedMybatisStoreBean() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        HikariDataSource dataSource = MybatisTestSupport.mysqlDataSource("test-handoff-wiring-pool");
        try {
            MybatisTestSupport.ensureSchema(dataSource);
            HandoffMapper mapper = MybatisTestSupport.mapper(dataSource, HandoffMapper.class);

            CustomerWorkProperties props = new CustomerWorkProperties();
            props.getHumanHandoff().setStoreMode("jdbc");

            new ApplicationContextRunner()
                .withBean(CustomerWorkProperties.class, () -> props)
                .withBean(HandoffMapper.class, () -> mapper)
                .withUserConfiguration(HandoffConfig.class)
                .withBean(HandoffService.class)
                .run(context -> {
                    HandoffStore store = context.getBean(HandoffStore.class);
                    assertInstanceOf(MybatisHandoffStore.class, store,
                        "store-mode=jdbc 应装配 MybatisHandoffStore");

                    HandoffService service = context.getBean(HandoffService.class);
                    String session = "wiring-jdbc-" + UUID.randomUUID();
                    HandoffTicket created = service.create(session, "wiring-test");

                    // 经独立 Mybatis 存储按 id 回读，证明 HandoffService 确实把工单落到了 cw_handoff_ticket
                    MybatisHandoffStore probe = new MybatisHandoffStore(mapper);
                    assertTrue(probe.find(created.getId()).isPresent(),
                        "HandoffService 应经注入的 MybatisHandoffStore 把工单落库 cw_handoff_ticket");
                });
        } finally {
            dataSource.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
