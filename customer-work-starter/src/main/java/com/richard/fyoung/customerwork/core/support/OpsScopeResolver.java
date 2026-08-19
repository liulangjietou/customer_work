package com.richard.fyoung.customerwork.core.support;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * 运营统计分区解析器：回答"这条运营指标该记在哪个分区、看板按哪个维度聚合"。
 *
 * <p><b>与 {@link TenantResolver} 是两种 scope，必须分开</b>。二者此前混用一个实现，
 * 导致运营看板长期查不到任何数据——这不是配置问题，是概念错配：</p>
 *
 * <ul>
 *   <li>{@code TenantResolver}：<b>数据分区</b>，从 sessionId 前缀解析。长期记忆、事实日志、
 *       语义缓存靠它做隔离。用户端 sessionId 形如 {@code u{userId}:conv-xxx}，
 *       所以它解析出来的其实是<b>用户</b>标识——对隔离而言这正是要的
 *       （语义缓存尤其：两个用户问同一句话，答案不一定相同，按用户分区是安全底线）；</li>
 *   <li>本类：<b>运营分区</b>，取当前租户上下文。CSAT、知识盲区这类指标要回答的是
 *       "我这条业务线整体怎么样"，按用户分区等于每人一张报表，运营根本无从下手——
 *       看板上那个"分区键"输入框，运营既不知道该填谁的用户 ID，填了也只是一个人的数据。</li>
 * </ul>
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
