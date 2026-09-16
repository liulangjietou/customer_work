package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.projection;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeBase;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.mapper.AiKnowledgeBaseMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.springframework.stereotype.Component;

/**
 * 知识权限的跨库写边界：Admin 持有 KB 行锁期间，先同步提交客服库封锁，再改变权限事实。
 *
 * <p>客服库使用门面独立数据源自动提交；封锁失败向上抛，Admin 事务回滚。
 * Admin 后续失败仍保留 BLOCKED，只有显式重新投影才能恢复目标版本。</p>
 */
@Component
public class KnowledgeProjectionAccessGuard {
    private final AiKnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeProjectionGatewayProvider gatewayProvider;

    public KnowledgeProjectionAccessGuard(AiKnowledgeBaseMapper knowledgeBaseMapper,
                                          KnowledgeProjectionGatewayProvider gatewayProvider) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.gatewayProvider = gatewayProvider;
    }

    /** 在调用方 Admin 事务中首先锁 KB；后续才允许锁源及读取当前文档。 */
    public AiKnowledgeBase lockActive(Long knowledgeBaseId) {
        AiKnowledgeBase row = lockIncludingDeleted(knowledgeBaseId);
        if (!Integer.valueOf(0).equals(row.getDeleted())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
        return row;
    }

    /** 同名软删除知识库复活时也持有原 KB 行锁，不能让旧投影随旧 ID 一起复活。 */
    public AiKnowledgeBase lockIncludingDeleted(Long knowledgeBaseId) {
        AiKnowledgeBase row = knowledgeBaseMapper.selectByIdForUpdate(knowledgeBaseId);
        if (row == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在: " + knowledgeBaseId);
        }
        return row;
    }

    /** 调用方已持有 KB 行锁；门面写入成功返回后，才允许改动 Admin 权限与文档。 */
    public void blockAll(Long knowledgeBaseId) {
        gatewayProvider.get().versionMapper().blockByKnowledgeBase(
            String.valueOf(knowledgeBaseId), System.currentTimeMillis());
    }
}
