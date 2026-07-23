package com.richard.fyoung.customeradmin.openapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelRobot;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelSession;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelSessionMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.openapi.dto.OpenChannelRobotVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 开放 API 渠道能力：机器人配置下发（含解密明文 + 版本号）、外部用户会话解析/重置、对话前的授权校验。
 *
 * <p>会话解析对同一外部用户复用同一 {@code sessionId} 保持多轮上下文；重置生成新 sessionId 开启新会话。
 * 并发首解析可能撞 (channelType, appKey, externalUserId) 唯一键，捕获 {@link DuplicateKeyException}
 * 后回查已存在记录返回，保证幂等（防御式编程单点收敛在此）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class OpenChannelService {

    private static final int STATUS_ENABLED = 1;
    private static final String SESSION_PREFIX = "ch-";

    private final AiChannelRobotMapper robotMapper;
    private final AiChannelSessionMapper sessionMapper;
    private final AesGcmCryptoUtil cryptoUtil;

    public OpenChannelService(AiChannelRobotMapper robotMapper, AiChannelSessionMapper sessionMapper,
                              AesGcmCryptoUtil cryptoUtil) {
        this.robotMapper = robotMapper;
        this.sessionMapper = sessionMapper;
        this.cryptoUtil = cryptoUtil;
    }

    /** 返回启用状态的渠道机器人（可选按 channelType 过滤），携带解密明文 appSecret 与版本号。 */
    public List<OpenChannelRobotVO> listRobots(String channelType) {
        LambdaQueryWrapper<AiChannelRobot> wrapper = new LambdaQueryWrapper<AiChannelRobot>()
            .eq(AiChannelRobot::getStatus, STATUS_ENABLED);
        if (StringUtils.hasText(channelType)) {
            wrapper.eq(AiChannelRobot::getChannelType, channelType);
        }
        List<AiChannelRobot> robots = robotMapper.selectList(wrapper);
        if (CollectionUtils.isEmpty(robots)) {
            return new ArrayList<>();
        }
        List<OpenChannelRobotVO> list = new ArrayList<>(robots.size());
        for (AiChannelRobot robot : robots) {
            list.add(toOpenVo(robot));
        }
        return list;
    }

    /** 解析外部用户对应的会话：已存在则复用，不存在则创建（sessionId = ch-<uuid>）。 */
    public String resolveSession(String channelType, String appKey, String externalUserId) {
        AiChannelSession existing = findSession(channelType, appKey, externalUserId);
        if (existing != null) {
            return existing.getSessionId();
        }
        String sessionId = newSessionId();
        try {
            sessionMapper.insert(buildSession(channelType, appKey, externalUserId, sessionId));
            return sessionId;
        } catch (DuplicateKeyException e) {
            // 并发首解析撞唯一键：回查已落地记录，保证幂等
            AiChannelSession raced = findSession(channelType, appKey, externalUserId);
            return raced != null ? raced.getSessionId() : sessionId;
        }
    }

    /** 重置外部用户会话：已存在则生成新 sessionId 覆盖，不存在则等同 resolve（创建）。 */
    public String resetSession(String channelType, String appKey, String externalUserId) {
        AiChannelSession existing = findSession(channelType, appKey, externalUserId);
        String sessionId = newSessionId();
        if (existing == null) {
            try {
                sessionMapper.insert(buildSession(channelType, appKey, externalUserId, sessionId));
                return sessionId;
            } catch (DuplicateKeyException e) {
                existing = findSession(channelType, appKey, externalUserId);
                if (existing == null) {
                    return sessionId;
                }
            }
        }
        existing.setSessionId(sessionId);
        sessionMapper.updateById(existing);
        return sessionId;
    }

    /**
     * 对话前授权校验：agentCode 必须有启用的渠道机器人绑定，否则拒绝
     * （防止开放 API 借 agentCode 任意调用未授权智能体）。
     */
    public void requireAgentBound(String agentCode) {
        boolean bound = robotMapper.exists(new LambdaQueryWrapper<AiChannelRobot>()
            .eq(AiChannelRobot::getAgentCode, agentCode)
            .eq(AiChannelRobot::getStatus, STATUS_ENABLED));
        if (!bound) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND,
                "no enabled channel robot bound to agent: " + agentCode);
        }
    }

    private AiChannelSession findSession(String channelType, String appKey, String externalUserId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<AiChannelSession>()
            .eq(AiChannelSession::getChannelType, channelType)
            .eq(AiChannelSession::getAppKey, appKey)
            .eq(AiChannelSession::getExternalUserId, externalUserId));
    }

    private AiChannelSession buildSession(String channelType, String appKey, String externalUserId, String sessionId) {
        AiChannelSession session = new AiChannelSession();
        session.setChannelType(channelType);
        session.setAppKey(appKey);
        session.setExternalUserId(externalUserId);
        session.setSessionId(sessionId);
        return session;
    }

    private String newSessionId() {
        return SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private OpenChannelRobotVO toOpenVo(AiChannelRobot robot) {
        OpenChannelRobotVO vo = new OpenChannelRobotVO();
        vo.setId(robot.getId());
        vo.setChannelType(robot.getChannelType());
        vo.setRobotName(robot.getRobotName());
        vo.setAppKey(robot.getAppKey());
        vo.setAppSecret(cryptoUtil.decrypt(robot.getAppSecretCipher()));
        vo.setRobotCode(robot.getRobotCode());
        vo.setAgentCode(robot.getAgentCode());
        vo.setVersion(robot.getUpdateTime() == null ? null
            : robot.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        return vo;
    }
}
