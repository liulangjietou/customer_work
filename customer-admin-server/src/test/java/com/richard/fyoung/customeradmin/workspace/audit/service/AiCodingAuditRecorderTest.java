package com.richard.fyoung.customeradmin.workspace.audit.service;

import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.mapper.AiCodingAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiCodingAuditRecorder} 单测：落库委托、createTime 兜底补全、
 * 落库失败只记日志不向业务主链路抛异常（审计是旁路能力）。
 * @author owlzhangfq@gmail.com
 */
class AiCodingAuditRecorderTest {

    private AiCodingAuditLogMapper mapper;
    private AiCodingAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        mapper = mock(AiCodingAuditLogMapper.class);
        recorder = new AiCodingAuditRecorder(mapper);
    }

    @Test
    void persist_shouldInsert_andBackfillCreateTime() {
        AiCodingAuditLog entry = new AiCodingAuditLog();
        recorder.persist(entry);

        ArgumentCaptor<AiCodingAuditLog> captor = ArgumentCaptor.forClass(AiCodingAuditLog.class);
        verify(mapper).insert(captor.capture());
        assertNotNull(captor.getValue().getCreateTime());
    }

    @Test
    void persist_shouldSwallowPersistenceFailure() {
        when(mapper.insert(any(AiCodingAuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> recorder.persist(new AiCodingAuditLog()));
    }
}
