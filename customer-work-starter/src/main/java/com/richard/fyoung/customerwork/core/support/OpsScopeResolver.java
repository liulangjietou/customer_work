package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * 运营统计分区解析器：回答"这条运营指标该记在哪个分区、看板按哪个维度聚合"。
 *
 * <p><b>运营分区与数据租户、记忆主体是三个不同维度</b>。此前运营指标误用了会话解析结果，
 * 导致运营看板长期查不到任何数据——这不是配置问题，是概念错配：</p>
 *
 * <ul>
 *   <li>{@link TenantResolver}：<b>数据租户</b>，请求链路优先使用接入层建立的可信
 *       {@link TenantContext}；只有无上下文的兼容链路才解析历史 sessionId 前缀。</li>
 *   <li>{@link MemorySubjectResolver}：<b>长期记忆主体</b>，在租户内继续按验签用户或完整会话、
 *       Agent 隔离。客户端可控的 sessionId 前缀不能充当用户身份。</li>
 *   <li>本类：<b>运营分区</b>，取当前租户上下文。CSAT、知识盲区这类指标要回答的是
 *       "我这条业务线整体怎么样"，按用户分区等于每人一张报表，运营根本无从下手——
 *       看板上那个"分区键"输入框，运营既不知道该填谁的用户 ID，填了也只是一个人的数据。</li>
 * </ul>
 *
 * <p>因此在可信请求中，本类与 {@code TenantResolver} 返回同一个租户是正确行为；
 * 隐私隔离不能靠伪造一个不同的“数据 scope”，而要由显式记忆主体键继续细分。</p>
 *
 * <p>缺上下文时回落 {@link TenantContext#DEFAULT} 而非抛错：运营指标是旁路数据，
 * 不该让统计缺上下文把主链路打断（与持久层的 fail-closed 刻意相反，理由同数据权限维度）。
 * 未开多租户时全量数据自然落在 {@code default} 分区，看板默认值即可直接看到——
 * 这也是"单租户系统"下唯一说得通的口径。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class OpsScopeResolver {

    /**
     * 解析当前运营统计分区。
     *
     * @return 当前租户码；无租户上下文时为 {@link TenantContext#DEFAULT}
     */
    public String resolve() {
        String tenantId = TenantContext.get();
        return tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT : tenantId;
    }
}
