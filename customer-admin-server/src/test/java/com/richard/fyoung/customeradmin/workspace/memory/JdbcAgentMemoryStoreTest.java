package com.richard.fyoung.customeradmin.workspace.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.workspace.memory.entity.AiAgentMemory;
import com.richard.fyoung.customeradmin.workspace.memory.mapper.AiAgentMemoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JdbcAgentMemoryStore} 单测：agent_code 单行 upsert（无行插入/有行更新）、load 空行语义、delete。
 * @author owlzhangfq@gmail.com
 */
class JdbcAgentMemoryStoreTest {

    private static final String AGENT_CODE = "memo-agent";

    private AiAgentMemoryMapper mapper;
    private JdbcAgentMemoryStore store;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiAgentMemory.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiAgentMemoryMapper.class);
        store = new JdbcAgentMemoryStore(mapper);
    }

    @Test
    void load_shouldReturnEmpty_whenRowAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertTrue(store.load(AGENT_CODE).isEmpty());
    }

    @Test
    void load_shouldReturnSnapshot_whenRowPresent() {
        AiAgentMemory row = new AiAgentMemory();
        row.setAgentCode(AGENT_CODE);
        row.setContent("记忆内容");
        row.setVersion(7L);
        row.setUpdateTime(LocalDateTime.of(2026, 7, 22, 10, 30));
        when(mapper.selectOne(any())).thenReturn(row);

        Optional<AgentMemorySnapshot> snapshot = store.load(AGENT_CODE);

        assertTrue(snapshot.isPresent());
        assertEquals("记忆内容", snapshot.get().content());
        assertEquals(LocalDateTime.of(2026, 7, 22, 10, 30), snapshot.get().updateTime());
        assertEquals(7L, snapshot.get().version());
    }

    @Test
    void compareAndSet_shouldInsert_whenExpectedVersionIsZero() {
        when(mapper.insertIfAbsent(AGENT_CODE, "first")).thenReturn(1);

        assertTrue(store.compareAndSet(AGENT_CODE, "first", 0L));

        verify(mapper).insertIfAbsent(AGENT_CODE, "first");
    }

    @Test
    void compareAndSet_shouldUpdateOnlyExpectedVersion() {
        when(mapper.updateIfVersion(AGENT_CODE, "new", 9L)).thenReturn(1);

        assertTrue(store.compareAndSet(AGENT_CODE, "new", 9L));

        verify(mapper).updateIfVersion(AGENT_CODE, "new", 9L);
    }

    @Test
    void delete_shouldDeleteByAgentCode() {
        store.delete(AGENT_CODE);

        verify(mapper).delete(any());
    }
}
