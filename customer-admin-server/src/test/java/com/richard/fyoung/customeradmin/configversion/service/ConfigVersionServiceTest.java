package com.richard.fyoung.customeradmin.configversion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.configversion.mapper.AiConfigVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigVersionService} 单测：版本号递增、内容未变不新增、旧版本标记取代、失败留痕。
 * @author owlzhangfq@gmail.com
 */
class ConfigVersionServiceTest {

    private AiConfigVersionMapper versionMapper;
    private ConfigVersionService service;

    @BeforeEach
    void setUp() {
        versionMapper = mock(AiConfigVersionMapper.class);
        service = new ConfigVersionService(versionMapper);
    }

    private AiConfigVersion existing(int version, String hash, String status) {
        AiConfigVersion v = new AiConfigVersion();
        v.setId(100L + version);
        v.setConfigType(ConfigType.AGENT.name());
        v.setTargetCode("agent-a");
        v.setVersion(version);
        v.setContentHash(hash);
        v.setStatus(status);
        v.setPublishScope(PublishScope.FULL.name());
        return v;
    }

    @Test
    void recordPublish_shouldStartFromVersionOne() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int version = service.recordPublish(ConfigType.AGENT, "agent-a", 1L, "{\"a\":1}",
            "data-id", PublishScope.FULL, null, null, null);

        assertEquals(1, version, "第一次发布应是 v1");
        verify(versionMapper).insert(any(AiConfigVersion.class));
    }

    @Test
    void recordPublish_shouldIncrementPerTarget() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(3, "old-hash", "PUBLISHED"));

        int version = service.recordPublish(ConfigType.AGENT, "agent-a", 1L, "{\"a\":2}",
            "data-id", PublishScope.FULL, null, null, null);

        assertEquals(4, version, "版本号应按目标单调递增");
    }

    @Test
    void recordPublish_shouldSkipWhenContentUnchanged() {
        String content = "{\"a\":1}";
        // 先发一次拿到该内容的 hash
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        service.recordPublish(ConfigType.AGENT, "agent-a", 1L, content,
            "data-id", PublishScope.FULL, null, null, null);
        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);
        verify(versionMapper).insert(captor.capture());
        String hash = captor.getValue().getContentHash();

        // 再用同样内容发布：不该产生新版本
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(1, hash, "PUBLISHED"));
        int version = service.recordPublish(ConfigType.AGENT, "agent-a", 1L, content,
            "data-id", PublishScope.FULL, null, null, null);

        assertEquals(1, version, "内容没变应复用既有版本号");
        // 重复发布同样内容只会把版本历史刷满噪音，让"这次改了什么"难以辨认
        verify(versionMapper, times(1)).insert(any(AiConfigVersion.class));
    }

    @Test
    void recordPublish_shouldCreateNewVersionWhenScopeChanges() {
        String content = "{\"a\":1}";
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        service.recordPublish(ConfigType.AGENT, "agent-a", 1L, content,
            "data-id", PublishScope.FULL, null, null, null);
        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);
        verify(versionMapper).insert(captor.capture());
        String hash = captor.getValue().getContentHash();

        // 同样内容但从全量改成灰度：这是一次真实的发布行为变化，必须留新版本
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(1, hash, "PUBLISHED"));
        int version = service.recordPublish(ConfigType.AGENT, "agent-a", 1L, content,
            "gray-data-id", PublishScope.GRAY, "[\"acme\"]", null, null);

        assertEquals(2, version, "范围变了就是一次新发布，哪怕内容一样");
    }

    @Test
    void recordPublish_shouldSupersedePreviousVersion() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(2, "old-hash", "PUBLISHED"));

        service.recordPublish(ConfigType.AGENT, "agent-a", 1L, "{\"b\":1}",
            "data-id", PublishScope.FULL, null, null, null);

        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);
        verify(versionMapper).updateById(captor.capture());
        assertEquals("SUPERSEDED", captor.getValue().getStatus(),
            "旧版本要标记为已取代，否则列表里看不出哪一版在生效");
    }

    @Test
    void recordPublish_shouldCarryRollbackSource() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(5, "old-hash", "PUBLISHED"));

        service.recordPublish(ConfigType.AGENT, "agent-a", 1L, "{\"old\":true}",
            "data-id", PublishScope.FULL, null, 2, "回滚至 v2");

        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals(2, captor.getValue().getSourceVersion(), "回滚产生的版本要记下来源，历史才追得回去");
        assertEquals(6, captor.getValue().getVersion(), "回滚是新版本而不是复活旧版本");
    }

    @Test
    void recordFailure_shouldNotSupersedeCurrentVersion() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(existing(3, "hash", "PUBLISHED"));

        service.recordFailure(ConfigType.AGENT, "agent-a", 1L, "{\"bad\":1}", "nacos unreachable");

        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        // 发布失败时线上跑的仍是上一版，不能把它标成已取代
        verify(versionMapper, never()).updateById(any(AiConfigVersion.class));
    }

    @Test
    void recordPublish_shouldHashDifferentContentDifferently() {
        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ArgumentCaptor<AiConfigVersion> captor = ArgumentCaptor.forClass(AiConfigVersion.class);

        service.recordPublish(ConfigType.AGENT, "a", 1L, "{\"x\":1}", "d", PublishScope.FULL, null, null, null);
        service.recordPublish(ConfigType.AGENT, "b", 2L, "{\"x\":2}", "d", PublishScope.FULL, null, null, null);

        verify(versionMapper, times(2)).insert(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getContentHash(),
            captor.getAllValues().get(1).getContentHash(), "不同内容的摘要必须不同，否则会误判为未变更");
    }

    @Test
    void detail_shouldReturnContentButListShouldNot() {
        AiConfigVersion entity = existing(1, "hash", "PUBLISHED");
        entity.setContent("{\"big\":\"payload\"}");
        when(versionMapper.selectById(101L)).thenReturn(entity);
        when(versionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(entity));

        assertEquals("{\"big\":\"payload\"}", service.detail(101L).getContent(), "详情要带完整快照供对比");
        assertNull(service.listByTarget(ConfigType.AGENT, "agent-a").get(0).getContent(),
            "列表不该带快照——一条可能几十 KB，既慢又没人看");
    }
}
