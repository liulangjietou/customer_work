package com.richard.fyoung.customeradmin.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelRobot;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelSession;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelSessionMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.openapi.dto.OpenChannelRobotVO;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OpenChannelService} 单测：机器人配置解密下发 + 版本号、会话解析幂等（复用同一 sessionId）、
 * 会话重置（生成新 sessionId 覆盖 + 无记录时创建）、对话授权校验。Mapper 用 mock，加解密用真实
 * {@link AesGcmCryptoUtil}。
 * @author owlzhangfq@gmail.com
 */
class OpenChannelServiceTest {

    private static final String CHANNEL = "dingtalk";
    private static final String APP_KEY = "app-key-1";
    private static final String USER = "user-42";
    private static final String PLAIN_SECRET = "app-secret-9999";

    private AiChannelRobotMapper robotMapper;
    private AiChannelSessionMapper sessionMapper;
    private AesGcmCryptoUtil cryptoUtil;
    private OpenChannelService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        Configuration cfg = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), AiChannelRobot.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), AiChannelSession.class);
    }

    @BeforeEach
    void setUp() {
        robotMapper = mock(AiChannelRobotMapper.class);
        sessionMapper = mock(AiChannelSessionMapper.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef0123456789abcdef");
        service = new OpenChannelService(robotMapper, sessionMapper, cryptoUtil);
    }

    @Test
    void listRobots_shouldDecryptSecret_andComputeVersion() {
        LocalDateTime updateTime = LocalDateTime.of(2026, 7, 23, 10, 0, 0);
        AiChannelRobot robot = new AiChannelRobot();
        robot.setId(1L);
        robot.setChannelType(CHANNEL);
        robot.setRobotName("钉钉客服");
        robot.setAppKey(APP_KEY);
        robot.setAgentCode("agent-x");
        robot.setAppSecretCipher(cryptoUtil.encrypt(PLAIN_SECRET));
        robot.setUpdateTime(updateTime);
        when(robotMapper.selectList(any())).thenReturn(List.of(robot));

        List<OpenChannelRobotVO> list = service.listRobots(CHANNEL);

        assertEquals(1, list.size());
        OpenChannelRobotVO vo = list.get(0);
        assertEquals(PLAIN_SECRET, vo.getAppSecret(), "开放 API 下发解密后的明文密钥");
        long expected = updateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, vo.getVersion());
    }

    @Test
    void resolveSession_shouldReuse_whenExists() {
        AiChannelSession existing = new AiChannelSession();
        existing.setSessionId("ch-existing");
        when(sessionMapper.selectOne(any())).thenReturn(existing);

        String sessionId = service.resolveSession(CHANNEL, APP_KEY, USER);

        assertEquals("ch-existing", sessionId);
        verify(sessionMapper, never()).insert(any(AiChannelSession.class));
    }

    @Test
    void resolveSession_shouldCreate_whenAbsent() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        String sessionId = service.resolveSession(CHANNEL, APP_KEY, USER);

        assertTrue(sessionId.startsWith("ch-"), "新会话 id 应以 ch- 前缀");
        verify(sessionMapper).insert(any(AiChannelSession.class));
    }

    @Test
    void resetSession_shouldOverwriteWithNewId_whenExists() {
        AiChannelSession existing = new AiChannelSession();
        existing.setId(5L);
        existing.setSessionId("ch-old");
        when(sessionMapper.selectOne(any())).thenReturn(existing);

        String sessionId = service.resetSession(CHANNEL, APP_KEY, USER);

        assertTrue(sessionId.startsWith("ch-"));
        assertNotEquals("ch-old", sessionId, "重置后 sessionId 应变化");
        verify(sessionMapper).updateById(any(AiChannelSession.class));
        verify(sessionMapper, never()).insert(any(AiChannelSession.class));
    }

    @Test
    void resetSession_shouldCreate_whenAbsent() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        String sessionId = service.resetSession(CHANNEL, APP_KEY, USER);

        assertTrue(sessionId.startsWith("ch-"));
        verify(sessionMapper).insert(any(AiChannelSession.class));
    }

    @Test
    void requireAgentBound_shouldThrow_whenNoEnabledBinding() {
        when(robotMapper.exists(any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
            () -> service.requireAgentBound("agent-x", CHANNEL, APP_KEY));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void requireAgentBound_shouldPassOnlyExactEnabledBinding() {
        when(robotMapper.exists(any())).thenReturn(true);

        service.requireAgentBound("agent-x", CHANNEL, APP_KEY);

        ArgumentCaptor<LambdaQueryWrapper<AiChannelRobot>> captor =
            ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(robotMapper).exists(captor.capture());
        LambdaQueryWrapper<AiChannelRobot> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        String normalizedSql = sql.replace("_", "").toLowerCase();
        assertTrue(normalizedSql.contains("agentcode"));
        assertTrue(normalizedSql.contains("channeltype"));
        assertTrue(normalizedSql.contains("appkey"));
        assertTrue(normalizedSql.contains("status"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("agent-x"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(CHANNEL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(APP_KEY));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(StatusFlags.ENABLED));
    }
}
