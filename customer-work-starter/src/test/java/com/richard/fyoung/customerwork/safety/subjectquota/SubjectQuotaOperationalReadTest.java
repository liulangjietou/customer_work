package com.richard.fyoung.customerwork.safety.subjectquota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaHitMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaLevelMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 运营查询失败必须可见，运行时快照加载则保留既有的可恢复失败契约。 */
class SubjectQuotaOperationalReadTest {
    @Test
    void operationalHitDetailsDoNotReportDatabaseFailureAsEmpty() {
        var mapper = mock(SubjectQuotaHitMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("acceptance database unavailable"));
        assertThrows(IllegalStateException.class, () -> new MybatisSubjectQuotaHitStore(mapper).findRecent("acceptance", 0, 20));
    }

    @Test
    void operationalHitRankDoesNotReportDatabaseFailureAsEmpty() {
        var mapper = mock(SubjectQuotaHitMapper.class);
        when(mapper.selectRank("acceptance", 0, 20)).thenThrow(new IllegalStateException("acceptance database unavailable"));
        assertThrows(IllegalStateException.class, () -> new MybatisSubjectQuotaHitStore(mapper).rank("acceptance", 0, 20));
    }

    @Test
    void operationalLookupDoesNotReportDatabaseFailureAsEmptyLevels() {
        var mapper = mock(SubjectQuotaLevelMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("acceptance database unavailable"));
        var store = new MybatisSubjectQuotaLevelStore(mapper);
        assertThrows(IllegalStateException.class, () -> store.findByTenant("acceptance-tenant"));
    }

    @Test
    void actuallyEmptyTenantStillReturnsAnEmptyLevelList() {
        var mapper = mock(SubjectQuotaLevelMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertEquals(List.of(), new MybatisSubjectQuotaLevelStore(mapper).findByTenant("acceptance-tenant"));
    }

    @Test
    void runtimeSnapshotFailureStillLetsProviderKeepItsPreviousSnapshot() {
        var mapper = mock(SubjectQuotaLevelMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("acceptance database unavailable"));
        assertTrue(new MybatisSubjectQuotaLevelStore(mapper).findAllEnabled().isEmpty());
    }
}
