package com.richard.fyoung.customerwork.sensitiveword;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 敏感词存储测试（对接本机 MySQL：localhost:3306，root/root，库 agent_scope_customer_work，
 * 表 cw_sensitive_word 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue），保证无 MySQL 环境仍通过。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisSensitiveWordStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private SensitiveWordMapper mapper;
    private MybatisSensitiveWordStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-sensitive-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        mapper = MybatisTestSupport.mapper(dataSource, SensitiveWordMapper.class);
        store = new MybatisSensitiveWordStore(mapper);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
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

    @Test
    void saveAndFindEnabled_shouldRoundTrip() {
        String word = "IT-敏感词-" + UUID.randomUUID();
        store.save(SensitiveWord.of(word, SensitiveWordCategory.COMPETITOR, SensitiveWordAction.MASK));

        assertTrue(store.findEnabled().orElseThrow().stream().anyMatch(w ->
            word.equals(w.getWord())
                && w.getCategory() == SensitiveWordCategory.COMPETITOR
                && w.getAction() == SensitiveWordAction.MASK));
    }

    @Test
    void seeds_shouldBePresentAfterSchemaInit() {
        assertTrue(store.findAll().stream().anyMatch(w -> "测试敏感词A".equals(w.getWord())),
            "建表脚本种子应存在");
    }

    @Test
    void sensitiveWordConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSensitiveWord().setStoreMode("jdbc");
        SensitiveWordStore selected = new SensitiveWordConfig().sensitiveWordStore(props, singletonProvider(mapper));
        assertInstanceOf(MybatisSensitiveWordStore.class, selected);
    }

    @Test
    void filterOverMysql_shouldBlockSeedWord() {
        SensitiveWordFilter filter = new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK);
        assertEquals(SensitiveWordAction.BLOCK, filter.check("我想问测试敏感词A").decision());
    }

    private static <T> ObjectProvider<T> singletonProvider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }
}
