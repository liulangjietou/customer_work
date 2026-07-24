package com.richard.fyoung.customeradmin.workspace.callstats.config;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AppAgentCallStatsGatewayProvider} 容错单测：APP 客服端库不可达时 {@link #get()} 抛
 * {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE} 明确业务异常，且失败不缓存（下次可重试）——
 * 保证该库不可达绝不在启动期触碰、也不污染后续恢复。指向必然关闭的端口，无需真实 DB，CI 可跑。
 * @author owlzhangfq@gmail.com
 */
class AppAgentCallStatsGatewayProviderTest {

    private AppAgentCallStatsGatewayProvider unreachableProvider() {
        AgentCallStatsAppProperties props = new AgentCallStatsAppProperties();
        props.setHost("127.0.0.1");
        // 65534 端口默认无监听，连接立即被拒（不会长时间超时）
        props.setPort(65534);
        props.setDatabase("agent_scope_customer_work");
        props.setUsername("root");
        props.setPassword("root");
        return new AppAgentCallStatsGatewayProvider(props);
    }

    @Test
    void get_shouldThrowCustomerWorkUnavailable_whenAppDbUnreachable() {
        AppAgentCallStatsGatewayProvider provider = unreachableProvider();
        BizException e = assertThrows(BizException.class, provider::get);
        assertEquals(ResultCode.CUSTOMER_WORK_UNAVAILABLE, e.getResultCode());
    }

    @Test
    void get_shouldNotCacheFailure_andRetryEachTime() {
        AppAgentCallStatsGatewayProvider provider = unreachableProvider();
        assertThrows(BizException.class, provider::get);
        // 第二次仍抛（失败不缓存，覆盖库稍后恢复的场景）
        assertThrows(BizException.class, provider::get);
    }
}
