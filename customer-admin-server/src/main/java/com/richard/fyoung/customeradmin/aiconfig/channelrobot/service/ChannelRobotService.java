package com.richard.fyoung.customeradmin.aiconfig.channelrobot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.ChannelType;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.SessionMode;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.dto.ChannelRobotVO;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelRobot;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 渠道机器人 CRUD：AppSecret 加密落库/留空不改、channelType 枚举校验、agentCode 必须绑定到启用的
 * 智能体（fast fail）。列表 VO 不回密文，仅回 {@code hasSecret} 布尔。
 *
 * <p>密码/密钥处理沿用 {@code WorkbenchSiteService} 手法（AES-GCM 密文入库，编辑留空复用原密文）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChannelRobotService {

    private static final int STATUS_ENABLED = 1;

    private final AiChannelRobotMapper robotMapper;
    private final AiAgentMapper agentMapper;
    private final AesGcmCryptoUtil cryptoUtil;

    public ChannelRobotService(AiChannelRobotMapper robotMapper, AiAgentMapper agentMapper,
                               AesGcmCryptoUtil cryptoUtil) {
        this.robotMapper = robotMapper;
        this.agentMapper = agentMapper;
        this.cryptoUtil = cryptoUtil;
    }

    /** 分页查询：channelType 精确筛选 + keyword 模糊匹配 robotName/appKey/agentCode，按创建时间倒序。 */
    public IPage<ChannelRobotVO> page(long current, long size, String channelType, String keyword) {
        LambdaQueryWrapper<AiChannelRobot> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(channelType)) {
            wrapper.eq(AiChannelRobot::getChannelType, channelType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(AiChannelRobot::getRobotName, keyword)
                .or().like(AiChannelRobot::getAppKey, keyword)
                .or().like(AiChannelRobot::getAgentCode, keyword));
        }
        wrapper.orderByDesc(AiChannelRobot::getCreateTime);
        IPage<AiChannelRobot> page = robotMapper.selectPage(new Page<>(current, size), wrapper);
        return page.convert(this::toVo);
    }

    public void create(ChannelRobotSaveRequest request) {
        validateChannelType(request.channelType());
        requireAgentEnabled(request.agentCode());
        requireAppKeyAvailable(request.channelType(), request.appKey(), null);
        if (!StringUtils.hasText(request.appSecret())) {
            throw new BizException(ResultCode.PARAM_MISSING, "appSecret 不能为空");
        }
        AiChannelRobot robot = new AiChannelRobot();
        fillFromRequest(robot, request);
        robot.setAppSecretCipher(cryptoUtil.encrypt(request.appSecret()));
        robotMapper.insert(robot);
    }

    public void update(Long id, ChannelRobotSaveRequest request) {
        AiChannelRobot robot = requireRobot(id);
        validateChannelType(request.channelType());
        requireAgentEnabled(request.agentCode());
        requireAppKeyAvailable(request.channelType(), request.appKey(), id);
        fillFromRequest(robot, request);
        // appSecret 留空/null=不修改密文，沿用原密文，避免每次编辑都要重输
        if (StringUtils.hasText(request.appSecret())) {
            robot.setAppSecretCipher(cryptoUtil.encrypt(request.appSecret()));
        }
        robotMapper.updateById(robot);
    }

    public void delete(Long id) {
        requireRobot(id);
        robotMapper.deleteById(id);
    }

    /** channelType 必须是已支持的渠道枚举（当前仅 dingtalk，wecom/wechat 预留未开放）。 */
    private void validateChannelType(String channelType) {
        ChannelType type = ChannelType.fromCode(channelType);
        if (type == null || !type.isSupported()) {
            throw new BizException(ResultCode.PARAM_INVALID, "暂不支持的渠道类型: " + channelType);
        }
    }

    /** agentCode 必须存在于 ai_agent 且启用，否则 fast fail。 */
    private void requireAgentEnabled(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
            .eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "绑定的智能体不存在: " + agentCode);
        }
        if (agent.getStatus() == null || agent.getStatus() != STATUS_ENABLED) {
            throw new BizException(ResultCode.AGENT_DISABLED, "绑定的智能体未启用: " + agentCode);
        }
    }

    /** (channelType, appKey) 唯一，冲突时给出友好错误（DB 唯一键的应用层前置校验）。 */
    private void requireAppKeyAvailable(String channelType, String appKey, Long excludeId) {
        LambdaQueryWrapper<AiChannelRobot> wrapper = new LambdaQueryWrapper<AiChannelRobot>()
            .eq(AiChannelRobot::getChannelType, channelType)
            .eq(AiChannelRobot::getAppKey, appKey);
        if (excludeId != null) {
            wrapper.ne(AiChannelRobot::getId, excludeId);
        }
        if (robotMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "同渠道下 appKey 已存在: " + appKey);
        }
    }

    private void fillFromRequest(AiChannelRobot robot, ChannelRobotSaveRequest request) {
        robot.setChannelType(request.channelType());
        robot.setRobotName(request.robotName());
        robot.setAppKey(request.appKey());
        robot.setRobotCode(request.robotCode());
        robot.setAgentCode(request.agentCode());
        robot.setSessionMode(resolveSessionMode(request.sessionMode()));
        robot.setRemark(request.remark());
        robot.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
    }

    /** 会话模式：空值取默认 continuous，非法值 fast fail。 */
    private String resolveSessionMode(String sessionMode) {
        if (!StringUtils.hasText(sessionMode)) {
            return SessionMode.CONTINUOUS.getCode();
        }
        SessionMode mode = SessionMode.fromCode(sessionMode);
        if (mode == null) {
            throw new BizException(ResultCode.PARAM_INVALID, "非法的会话模式: " + sessionMode);
        }
        return mode.getCode();
    }

    private ChannelRobotVO toVo(AiChannelRobot robot) {
        ChannelRobotVO vo = new ChannelRobotVO();
        vo.setId(robot.getId());
        vo.setChannelType(robot.getChannelType());
        vo.setRobotName(robot.getRobotName());
        vo.setAppKey(robot.getAppKey());
        vo.setRobotCode(robot.getRobotCode());
        vo.setAgentCode(robot.getAgentCode());
        vo.setSessionMode(robot.getSessionMode());
        vo.setStatus(robot.getStatus());
        vo.setRemark(robot.getRemark());
        vo.setHasSecret(StringUtils.hasText(robot.getAppSecretCipher()));
        vo.setCreateTime(robot.getCreateTime());
        vo.setUpdateTime(robot.getUpdateTime());
        return vo;
    }

    private AiChannelRobot requireRobot(Long id) {
        AiChannelRobot robot = robotMapper.selectById(id);
        if (robot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "渠道机器人不存在: " + id);
        }
        return robot;
    }
}
