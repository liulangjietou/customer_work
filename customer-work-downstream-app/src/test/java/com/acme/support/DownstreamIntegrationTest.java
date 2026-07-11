package com.acme.support;

import com.richard.fyoung.customerwork.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.service.CustomerServiceService;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 下游接入契约测试：证明在一个**完全不同包名**的应用里——
 * 1) 仅依赖 starter 即可自动装配其全部能力 Bean（零 @ComponentScan）；
 * 2) 下游自定义的 {@link AcmeOrderBackend} 覆盖了 starter 的默认 Mock 实现。
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DownstreamIntegrationTest {

    @Autowired
    private CustomerServiceService customerServiceService;   // 来自 starter，经自动配置注入

    @Autowired
    private CustomerServiceAgentFactory agentFactory;        // 来自 starter

    @Autowired
    private OrderBackend orderBackend;                        // 应为下游自定义实现

    @Test
    void starterBeansAutowired_andCustomBackendOverridesDefault() {
        assertNotNull(customerServiceService, "starter 的 CustomerServiceService 应被自动装配");
        assertNotNull(agentFactory, "starter 的 CustomerServiceAgentFactory 应被自动装配");
        assertInstanceOf(AcmeOrderBackend.class, orderBackend,
            "下游自定义 OrderBackend 应覆盖 starter 默认 Mock");
        // 能装配出 Agent，说明工具/记忆/RAG/Hook 等全链路装配成功
        assertNotNull(agentFactory.createAgent("acme:conv-1"));
    }
}
