package com.richard.fyoung.customerwork.dict;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.dict.entity.DictItemEntity;
import com.richard.fyoung.customerwork.dict.entity.DictTypeEntity;
import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;
import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis-Plus 字典存储测试（对接本机 MySQL：localhost:3306，root/root，
 * 库 agent_scope_customer_work，表 cw_dict_type / cw_dict_item 由 {@link MybatisTestSupport#ensureSchema} 建好）。
 *
 * <p>MySQL 不可达时自动跳过（assumeTrue）；MySQL 在线的机器上真实执行写入-读取往返，
 * 验证 {@link MybatisDictStore} 的启用过滤与排序语义。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisDictStoreTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private DictTypeMapper typeMapper;
    private DictItemMapper itemMapper;
    private MybatisDictStore store;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");

        dataSource = MybatisTestSupport.mysqlDataSource("test-dict-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        typeMapper = MybatisTestSupport.mapper(dataSource, DictTypeMapper.class);
        itemMapper = MybatisTestSupport.mapper(dataSource, DictItemMapper.class);
        store = new MybatisDictStore(typeMapper, itemMapper);
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
    void findEnabledItems_shouldFilterDisabled_andSortAsc() {
        String dictType = "it_dict_" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        try {
            insertType(dictType, now);
            insertItem(dictType, "k2", "标签2", 2, true, now);
            insertItem(dictType, "k1", "标签1", 1, true, now);
            insertItem(dictType, "k3", "停用项", 3, false, now);

            List<DictItem> items = store.findEnabledItems(dictType);
            assertEquals(2, items.size(), "停用项不应返回");
            assertEquals("k1", items.get(0).itemKey(), "应按 sort 升序");
            assertEquals("k2", items.get(1).itemKey());

            List<DictType> types = store.listEnabledTypes();
            assertTrue(types.stream().anyMatch(t -> dictType.equals(t.dictType())), "启用类型应可列出");
        } finally {
            cleanup(dictType);
        }
    }

    @Test
    void findEnabledItems_shouldReturnEmpty_forUnknownType() {
        assertTrue(store.findEnabledItems("no_such_type_" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void dictConfig_shouldSelectMybatisStore_whenStoreModeIsJdbc() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getDict().setStoreMode("jdbc");

        DictStore selected = new DictConfig().dictStore(props,
            singletonProvider(typeMapper), singletonProvider(itemMapper));
        assertInstanceOf(MybatisDictStore.class, selected, "store-mode=jdbc 应装配 MybatisDictStore");
    }

    private void insertType(String dictType, long now) {
        DictTypeEntity type = new DictTypeEntity();
        type.setDictType(dictType);
        type.setTypeName("集成测试类型");
        type.setEnabled(true);
        type.setCreatedAtMs(now);
        type.setUpdatedAtMs(now);
        typeMapper.insert(type);
    }

    private void insertItem(String dictType, String key, String label, int sort, boolean enabled, long now) {
        DictItemEntity item = new DictItemEntity();
        item.setDictType(dictType);
        item.setItemKey(key);
        item.setItemLabel(label);
        item.setSort(sort);
        item.setEnabled(enabled);
        item.setCreatedAtMs(now);
        item.setUpdatedAtMs(now);
        itemMapper.insert(item);
    }

    private void cleanup(String dictType) {
        itemMapper.delete(new QueryWrapper<DictItemEntity>().eq("dict_type", dictType));
        typeMapper.delete(new QueryWrapper<DictTypeEntity>().eq("dict_type", dictType));
    }

    /** 最小 {@link ObjectProvider}：仅 {@code getObject()} 返回给定 Bean，供 Config 单测在无 Spring 容器下取 Mapper。 */
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
