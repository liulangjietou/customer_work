package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 登录滑块存储只允许在启动选型时二选一。 */
class AuthGuardConfigLoginCaptchaTest {

    private final AuthGuardConfig config = new AuthGuardConfig();
    private final LoginCaptchaProperties properties = new LoginCaptchaProperties();
    private final ApplicationContextRunner clientIpContextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ClientIpTestConfig.class);

    @Test
    void loginCaptchaStore_shouldUsePureInMemoryWhenRedissonBeanIsAbsent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        LoginCaptchaStore store = config.loginCaptchaStore(provider, properties);

        assertInstanceOf(InMemoryLoginCaptchaStore.class, store);
    }

    @Test
    void loginCaptchaStore_shouldApplyConfiguredInMemoryCapacity() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        properties.setMaxInMemoryEntries(1);
        LoginCaptchaStore store = config.loginCaptchaStore(provider, properties);
        long expireAtMs = System.currentTimeMillis() + 60_000L;

        store.saveProof("proof-1", new LoginCaptchaStore.ProofState("fingerprint", expireAtMs), 60);

        assertThrows(IllegalStateException.class, () -> store.saveProof(
            "proof-2", new LoginCaptchaStore.ProofState("fingerprint", expireAtMs), 60));
    }

    @Test
    void loginCaptchaStore_shouldUseRedisOnlyWhenRedissonBeanIsPresent() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(RedissonClient.class));

        LoginCaptchaStore store = config.loginCaptchaStore(provider, properties);

        assertInstanceOf(RedissonLoginCaptchaStore.class, store);
    }

    @Test
    void clientIpResolver_shouldRequireContainerForwardHeadersToRemainDisabled() {
        RegistrationGuardProperties registration = new RegistrationGuardProperties();
        ServerProperties safeServer = new ServerProperties();
        safeServer.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
        assertNotNull(config.clientIpResolver(registration, safeServer));

        for (ServerProperties.ForwardHeadersStrategy unsafe : java.util.List.of(
            ServerProperties.ForwardHeadersStrategy.FRAMEWORK,
            ServerProperties.ForwardHeadersStrategy.NATIVE)) {
            ServerProperties unsafeServer = new ServerProperties();
            unsafeServer.setForwardHeadersStrategy(unsafe);
            assertThrows(IllegalStateException.class,
                () -> config.clientIpResolver(registration, unsafeServer));
        }

        ServerProperties remoteIpHeaderServer = serverPropertiesWithoutForwardHeaders();
        remoteIpHeaderServer.getTomcat().getRemoteip().setRemoteIpHeader("X-Forwarded-For");
        assertThrows(IllegalStateException.class,
            () -> config.clientIpResolver(registration, remoteIpHeaderServer));

        ServerProperties protocolHeaderServer = serverPropertiesWithoutForwardHeaders();
        protocolHeaderServer.getTomcat().getRemoteip().setProtocolHeader("X-Forwarded-Proto");
        assertThrows(IllegalStateException.class,
            () -> config.clientIpResolver(registration, protocolHeaderServer));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "server.forward-headers-strategy=framework",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.remote-ip-header=X-Forwarded-For",
        "server.tomcat.remoteip.protocol-header=X-Forwarded-Proto"
    })
    void clientIpResolver_shouldFailStartupForUnsafeBoundContainerSetting(String propertyValue) {
        clientIpContextRunner.withPropertyValues(propertyValue).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseMessage(
                    "container forward-header handling must remain disabled; ClientIpResolver is the only trust boundary");
        });
    }

    @Test
    void clientIpResolver_shouldStartWhenBoundContainerHandlingIsDisabled() {
        clientIpContextRunner
            .withPropertyValues("server.forward-headers-strategy=none")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ClientIpResolver.class);
            });
    }

    private ServerProperties serverPropertiesWithoutForwardHeaders() {
        ServerProperties serverProperties = new ServerProperties();
        serverProperties.setForwardHeadersStrategy(ServerProperties.ForwardHeadersStrategy.NONE);
        return serverProperties;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RegistrationGuardProperties.class, ServerProperties.class})
    static class ClientIpTestConfig {

        @Bean
        ClientIpResolver clientIpResolver(RegistrationGuardProperties registration,
                                          ServerProperties serverProperties) {
            return new AuthGuardConfig().clientIpResolver(registration, serverProperties);
        }
    }
}
