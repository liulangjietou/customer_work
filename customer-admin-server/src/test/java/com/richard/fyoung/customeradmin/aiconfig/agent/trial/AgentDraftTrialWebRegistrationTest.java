package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.subjectquota.config.AdminQuotaWebConfig;
import com.richard.fyoung.customeradmin.subjectquota.config.AdminSubjectQuotaProperties;
import com.richard.fyoung.customeradmin.workspace.security.AdminAgentIdentityWebConfig;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/** 实际 Spring 路径匹配，防止只新增了控制器但漏挂身份或重复收取回执请求额度。 */
class AgentDraftTrialWebRegistrationTest {
    @Test
    void allTrialRoutesEstablishIdentityIndependentlyOfQuotaEnablement() {
        var registry = new Registry();
        new AdminAgentIdentityWebConfig().addInterceptors(registry);
        for (String path : List.of("/api/aiconfig/agent-drafts/draft/trials",
            "/api/aiconfig/agent-drafts/draft/trials/preview", "/api/aiconfig/agent-drafts/draft/trials/trial")) {
            assertTrue(registry.matches(path), path);
        }
    }

    @Test
    void evenBroadQuotaConfigurationDoesNotChargeTrialReceiptReadsOrDuplicatePuts() {
        var properties = new AdminSubjectQuotaProperties();
        properties.setEnabled(true); properties.setPathPatterns(List.of("/api/**"));
        var registry = new Registry();
        new AdminQuotaWebConfig(mock(SubjectQuotaGuard.class), new ObjectMapper(), properties).addInterceptors(registry);
        assertFalse(registry.matches("/api/aiconfig/agent-drafts/draft/trials"));
        assertFalse(registry.matches("/api/aiconfig/agent-drafts/draft/trials/trial"));
        assertTrue(registry.matches("/api/workspace/trial/chat/stream"));
    }

    private static class Registry extends InterceptorRegistry {
        boolean matches(String path) {
            var request = new MockHttpServletRequest("GET", path);
            ServletRequestPathUtils.parseAndCache(request);
            return getInterceptors().stream().map(MappedInterceptor.class::cast).anyMatch(item -> item.matches(request));
        }
    }
}
