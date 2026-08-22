package com.richard.fyoung.customeradmin.tenant.access;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.entity.AiChannelBinding;
import com.richard.fyoung.customeradmin.aiconfig.channel.mapper.AiChannelBindingMapper;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.entity.AiChannelRobot;
import com.richard.fyoung.customeradmin.aiconfig.channelrobot.mapper.AiChannelRobotMapper;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 退租事务内停用全部渠道入口；失败直接抛出并回滚租户终止状态。 */
@Service
public class TenantChannelDisableService {

    private final AiChannelBindingMapper bindingMapper;
    private final AiChannelRobotMapper robotMapper;

    public TenantChannelDisableService(AiChannelBindingMapper bindingMapper,
                                       AiChannelRobotMapper robotMapper) {
        this.bindingMapper = bindingMapper;
        this.robotMapper = robotMapper;
    }

    public int disableForOffboarding(String tenantId) {
        return TenantContext.callWith(tenantId, () -> {
            int bindings = bindingMapper.update(null, new UpdateWrapper<AiChannelBinding>()
                .set("status", StatusFlags.DISABLED)
                .eq("status", StatusFlags.ENABLED));
            int robots = robotMapper.update(null, new UpdateWrapper<AiChannelRobot>()
                .set("status", StatusFlags.DISABLED)
                .eq("status", StatusFlags.ENABLED));
            return bindings + robots;
        });
    }
}
