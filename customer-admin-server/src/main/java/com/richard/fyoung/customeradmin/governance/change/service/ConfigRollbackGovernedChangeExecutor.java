package com.richard.fyoung.customeradmin.governance.change.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.configversion.service.ConfigRollbackService;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeType;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 配置回滚/灰度审批通过后的唯一执行入口。 */
@Component
public class ConfigRollbackGovernedChangeExecutor implements GovernedChangeExecutor {

    private final ConfigRollbackService rollbackService;
    private final ObjectMapper objectMapper;
    public ConfigRollbackGovernedChangeExecutor(ConfigRollbackService rollbackService,
                                                ObjectMapper objectMapper) {
        this.rollbackService = rollbackService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<GovernedChangeType> types() {
        return Set.of(GovernedChangeType.CONFIG_ROLLBACK, GovernedChangeType.CONFIG_GRAY_RELEASE);
    }

    @Override
    public Object execute(AiGovernedChangeRequest request) {
        try {
            if (GovernedChangeType.CONFIG_ROLLBACK.name().equals(request.getChangeType())) {
                ConfigRollbackCommand command = objectMapper.readValue(
                    request.getPayloadJson(), ConfigRollbackCommand.class);
                return rollbackService.rollback(command.versionId(), command.remark());
            }
            ConfigGrayReleaseCommand command = objectMapper.readValue(
                request.getPayloadJson(), ConfigGrayReleaseCommand.class);
            return rollbackService.grayRelease(command.versionId(), command.tenantCodes(), command.remark());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("invalid governed config command", e);
        }
    }

    public record ConfigRollbackCommand(Long versionId, String remark) {
    }

    public record ConfigGrayReleaseCommand(Long versionId, List<String> tenantCodes, String remark) {
    }
}
