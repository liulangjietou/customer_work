package com.richard.fyoung.customeradmin.aiconfig.channelrobot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotVO;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelRobot;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Base64;

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
 * {@link ChannelRobotService} 单测：AppSecret 加密落库/留空不改、channelType 枚举校验、
 * agentCode 存在且启用的 fast fail、分页 VO 不回密文只回 hasSecret。Mapper 用 mock，
 * 加解密用真实 {@link AesGcmCryptoUtil} 验证密文可解回明文。
 * @author owlzhangfq@gmail.com
 */
class ChannelRobotServiceTest {

    private static final String PLAIN_SECRET = "app-secret-1234";
    private static final String ENCODING_AES_KEY =
        Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);

    private AiChannelRobotMapper robotMapper;
    private AiAgentMapper agentMapper;
    private AesGcmCryptoUtil cryptoUtil;
    private ChannelRobotService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        Configuration cfg = new Configuration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), AiChannelRobot.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(cfg, ""), AiAgent.class);
    }

    @BeforeEach
    void setUp() {
        robotMapper = mock(AiChannelRobotMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef0123456789abcdef");
        service = new ChannelRobotService(robotMapper, agentMapper, cryptoUtil);
    }

    private ChannelRobotSaveRequest request(String channelType, String appSecret) {
        return request(channelType, appSecret, null, null);
    }

    private ChannelRobotSaveRequest request(String channelType, String appSecret,
                                             String callbackMode, String encodingAesKey) {
        return new ChannelRobotSaveRequest(channelType, "钉钉客服", "app-key-1", appSecret,
            "robot-code-1", callbackMode, encodingAesKey, "agent-x", null, 1, "备注");
    }

    private void mockEnabledAgent() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("agent-x");
        agent.setStatus(1);
        when(agentMapper.selectOne(any())).thenReturn(agent);
    }

    @Test
    void create_shouldEncryptSecret_notStorePlaintext() {
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.create(request("dingtalk", PLAIN_SECRET));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).insert(captor.capture());
        AiChannelRobot saved = captor.getValue();
        assertNotEquals(PLAIN_SECRET, saved.getAppSecretCipher(), "落库密钥必须是密文");
        assertEquals(PLAIN_SECRET, cryptoUtil.decrypt(saved.getAppSecretCipher()), "密文应能被同一 util 解回明文");
    }

    @Test
    void create_shouldThrowParamMissing_whenSecretBlank() {
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("dingtalk", null)));
        assertEquals(ResultCode.PARAM_MISSING, ex.getResultCode());
        verify(robotMapper, never()).insert(any(AiChannelRobot.class));
    }

    @Test
    void create_shouldThrowParamInvalid_whenChannelTypeUnsupported() {
        BizException ex = assertThrows(BizException.class, () -> service.create(request("wecom", PLAIN_SECRET)));
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }

    @Test
    void create_shouldThrowNotFound_whenAgentMissing() {
        when(agentMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("dingtalk", PLAIN_SECRET)));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void create_shouldThrowAgentDisabled_whenAgentNotEnabled() {
        AiAgent agent = new AiAgent();
        agent.setAgentCode("agent-x");
        agent.setStatus(0);
        when(agentMapper.selectOne(any())).thenReturn(agent);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("dingtalk", PLAIN_SECRET)));
        assertEquals(ResultCode.AGENT_DISABLED, ex.getResultCode());
    }

    @Test
    void create_shouldThrowDuplicate_whenAppKeyExists() {
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("dingtalk", PLAIN_SECRET)));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
        verify(robotMapper, never()).insert(any(AiChannelRobot.class));
    }

    @Test
    void update_shouldKeepOriginalCipher_whenSecretBlank() {
        AiChannelRobot existing = new AiChannelRobot();
        existing.setId(1L);
        String originalCipher = cryptoUtil.encrypt(PLAIN_SECRET);
        existing.setAppSecretCipher(originalCipher);
        when(robotMapper.selectById(1L)).thenReturn(existing);
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.update(1L, request("dingtalk", null));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getAppSecretCipher(), "留空不改，密文应保持原值");
    }

    @Test
    void update_shouldReEncrypt_whenSecretPresent() {
        AiChannelRobot existing = new AiChannelRobot();
        existing.setId(1L);
        existing.setAppSecretCipher(cryptoUtil.encrypt("old-secret"));
        when(robotMapper.selectById(1L)).thenReturn(existing);
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.update(1L, request("dingtalk", "new-secret"));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).updateById(captor.capture());
        assertEquals("new-secret", cryptoUtil.decrypt(captor.getValue().getAppSecretCipher()));
    }

    @Test
    void delete_shouldThrowNotFound_whenMissing() {
        when(robotMapper.selectById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
        verify(robotMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void page_shouldMapVo_withHasSecret_andNoPlaintext() {
        AiChannelRobot robot = new AiChannelRobot();
        robot.setId(1L);
        robot.setChannelType("dingtalk");
        robot.setRobotName("钉钉客服");
        robot.setAppKey("app-key-1");
        robot.setAgentCode("agent-x");
        robot.setStatus(1);
        robot.setAppSecretCipher(cryptoUtil.encrypt(PLAIN_SECRET));
        Page<AiChannelRobot> page = new Page<>(1, 10);
        page.setRecords(List.of(robot));
        page.setTotal(1);
        when(robotMapper.selectPage(any(IPage.class), any())).thenReturn(page);

        IPage<ChannelRobotVO> result = service.page(1, 10, "dingtalk", "钉钉");

        assertEquals(1, result.getTotal());
        ChannelRobotVO vo = result.getRecords().get(0);
        assertEquals("钉钉客服", vo.getRobotName());
        assertTrue(vo.getHasSecret(), "已配置密钥时 hasSecret=true");
    }

    @Test
    void createWechatSafeModeShouldEncryptEncodingAesKey() {
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.create(request("wechat", PLAIN_SECRET, "safe", ENCODING_AES_KEY));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).insert(captor.capture());
        AiChannelRobot saved = captor.getValue();
        assertEquals("safe", saved.getCallbackMode());
        assertNotEquals(ENCODING_AES_KEY, saved.getEncodingAesKeyCipher());
        assertEquals(ENCODING_AES_KEY, cryptoUtil.decrypt(saved.getEncodingAesKeyCipher()));
    }

    @Test
    void createWechatSafeModeShouldRequireValidEncodingAesKey() {
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        BizException missing = assertThrows(BizException.class,
            () -> service.create(request("wechat", PLAIN_SECRET, "safe", null)));
        BizException invalid = assertThrows(BizException.class,
            () -> service.create(request("wechat", PLAIN_SECRET, "safe", "invalid")));

        assertEquals(ResultCode.PARAM_MISSING, missing.getResultCode());
        assertEquals(ResultCode.PARAM_INVALID, invalid.getResultCode());
        verify(robotMapper, never()).insert(any(AiChannelRobot.class));
    }

    @Test
    void updateWechatSafeModeShouldKeepEncodingKeyWhenBlank() {
        AiChannelRobot existing = new AiChannelRobot();
        existing.setId(1L);
        existing.setAppSecretCipher(cryptoUtil.encrypt(PLAIN_SECRET));
        String originalCipher = cryptoUtil.encrypt(ENCODING_AES_KEY);
        existing.setEncodingAesKeyCipher(originalCipher);
        when(robotMapper.selectById(1L)).thenReturn(existing);
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.update(1L, request("wechat", null, "safe", null));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getEncodingAesKeyCipher());
    }

    @Test
    void updateWechatToPlaintextShouldRemoveObsoleteEncodingKey() {
        AiChannelRobot existing = new AiChannelRobot();
        existing.setId(1L);
        existing.setAppSecretCipher(cryptoUtil.encrypt(PLAIN_SECRET));
        existing.setEncodingAesKeyCipher(cryptoUtil.encrypt(ENCODING_AES_KEY));
        when(robotMapper.selectById(1L)).thenReturn(existing);
        mockEnabledAgent();
        when(robotMapper.exists(any())).thenReturn(false);

        service.update(1L, request("wechat", null, "plaintext", null));

        ArgumentCaptor<AiChannelRobot> captor = ArgumentCaptor.forClass(AiChannelRobot.class);
        verify(robotMapper).updateById(captor.capture());
        assertEquals(null, captor.getValue().getEncodingAesKeyCipher());
    }
}
