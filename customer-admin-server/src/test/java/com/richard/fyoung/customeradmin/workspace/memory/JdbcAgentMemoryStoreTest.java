package com.richard.fyoung.customeradmin.workspace.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.workspace.memory.entity.AiAgentMemory;
import com.richard.fyoung.customeradmin.workspace.memory.mapper.AiAgentMemoryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        row.setUpdateTime(LocalDateTime.of(2026, 7, 22, 10, 30));
        when(mapper.selectOne(any())).thenReturn(row);

        Optional<AgentMemorySnapshot> snapshot = store.load(AGENT_CODE);

        assertTrue(snapshot.isPresent());
        assertEquals("记忆内容", snapshot.get().content());
        assertEquals(LocalDateTime.of(2026, 7, 22, 10, 30), snapshot.get().updateTime());
    }

    @Test
    void save_shouldInsert_whenRowAbsent() {
        when(mapper.selectOne(any())).thenReturn(null);

        store.save(AGENT_CODE, "first");

        ArgumentCaptor<AiAgentMemory> captor = ArgumentCaptor.forClass(AiAgentMemory.class);
        verify(mapper).insert(captor.capture());
        assertEquals(AGENT_CODE, captor.getValue().getAgentCode());
        assertEquals("first", captor.getValue().getContent());
        verify(mapper, never()).updateById(any(AiAgentMemory.class));
    }

    @Test
    void save_shouldUpdate_whenRowPresent() {
        AiAgentMemory row = new AiAgentMemory();
        row.setId(9L);
        row.setAgentCode(AGENT_CODE);
        row.setContent("old");
        when(mapper.selectOne(any())).thenReturn(row);

        store.save(AGENT_CODE, "new");

        ArgumentCaptor<AiAgentMemory> captor = ArgumentCaptor.forClass(AiAgentMemory.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("new", captor.getValue().getContent());
        verify(mapper, never()).insert(any(AiAgentMemory.class));
    }

    @Test
    void delete_shouldDeleteByAgentCode() {
        store.delete(AGENT_CODE);

        verify(mapper).delete(any());
    }
}
