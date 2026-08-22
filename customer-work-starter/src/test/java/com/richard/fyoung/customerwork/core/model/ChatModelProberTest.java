package com.richard.fyoung.customerwork.core.model;

import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四厂商最小探活协议、端点策略、禁重定向与路径编码测试。 */
class ChatModelProberTest {

    private static final String PUBLIC_BASE_URL = "https://8.8.8.8";
    private static final MediaType JSON = MediaType.get("application/json");

    @Test
    void probeOpenAiShouldSucceedAndHitChatCompletions() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ChatModelProber.ProbeResult result = prober(200,
            "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}", captured)
            .probe("openai", PUBLIC_BASE_URL, "sk-test", "gpt-4o-mini");

        assertTrue(result.success());
        assertTrue(captured.get().url().encodedPath().endsWith("/chat/completions"));
        assertEquals("Bearer sk-test", captured.get().header("Authorization"));
    }

    @Test
    void probeDashScopeShouldUseNativeEndpoint() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ChatModelProber.ProbeResult result = prober(200,
            "{\"output\":{\"choices\":[]},\"usage\":{}}", captured)
            .probe("dashscope", PUBLIC_BASE_URL, "sk-ds", "qwen-max");

        assertTrue(result.success());
        assertTrue(captured.get().url().encodedPath()
            .endsWith("/api/v1/services/aigc/text-generation/generation"));
        assertEquals("Bearer sk-ds", captured.get().header("Authorization"));
    }

    @Test
    void probeAnthropicShouldCarryApiKeyAndVersionHeaders() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ChatModelProber.ProbeResult result = prober(200,
            "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"role\":\"assistant\"}", captured)
            .probe("anthropic", PUBLIC_BASE_URL, "sk-ant", "claude-3-5-sonnet-latest");

        assertTrue(result.success());
        assertTrue(captured.get().url().encodedPath().endsWith("/v1/messages"));
        assertEquals("sk-ant", captured.get().header("x-api-key"));
        assertEquals("2023-06-01", captured.get().header("anthropic-version"));
    }

    @Test
    void probeGeminiShouldEncodeModelAsOnePathSegmentAndKeepKeyOutOfUrl() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ChatModelProber.ProbeResult result = prober(200,
            "{\"candidates\":[{\"content\":{\"parts\":[]}}]}", captured)
            .probe("gemini", PUBLIC_BASE_URL, "sk-gm", "gemini/../../victim?x=1");

        assertTrue(result.success());
        assertEquals("/v1beta/models/gemini%2F..%2F..%2Fvictim%3Fx%3D1:generateContent",
            captured.get().url().encodedPath());
        assertEquals("sk-gm", captured.get().header("x-goog-api-key"));
        assertFalse(captured.get().url().toString().contains("sk-gm"));
    }

    @Test
    void unknownProviderShouldFallBackToOpenAiCompatibleProtocol() {
        AtomicReference<Request> captured = new AtomicReference<>();
        ChatModelProber.ProbeResult result = prober(200, "{\"choices\":[]}", captured)
            .probe(null, PUBLIC_BASE_URL, "sk-test", "any-model");

        assertTrue(result.success());
        assertTrue(captured.get().url().encodedPath().endsWith("/chat/completions"));
    }

    @Test
    void responseMissingExpectedStructureShouldFail() {
        ChatModelProber.ProbeResult result = prober(200, "{\"error\":\"no choices\"}", null)
            .probe("openai", PUBLIC_BASE_URL, "sk-test", "gpt-4o-mini");

        assertFalse(result.success());
    }

    @Test
    void httpErrorShouldFailWithStatus() {
        ChatModelProber.ProbeResult result = prober(401, "unauthorized", null)
            .probe("anthropic", PUBLIC_BASE_URL, "sk-test", "claude");

        assertFalse(result.success());
        assertTrue(result.message().contains("401"));
    }

    @Test
    void timeoutShouldReturnStableFailure() {
        Interceptor timeout = chain -> {
            throw new SocketTimeoutException("test timeout");
        };
        ChatModelProber prober = new ChatModelProber(Duration.ofSeconds(1), strictPolicy(), timeout);

        ChatModelProber.ProbeResult result = prober.probe(
            "openai", PUBLIC_BASE_URL, "sk-test", "gpt-4o-mini");

        assertFalse(result.success());
        assertTrue(result.message().contains("超时"));
    }

    @Test
    void privateEndpointShouldFailBeforeInterceptorSeesCredential() {
        AtomicInteger calls = new AtomicInteger();
        Interceptor interceptor = chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, "{\"choices\":[]}");
        };
        ChatModelProber prober = new ChatModelProber(Duration.ofSeconds(1), strictPolicy(), interceptor);

        ChatModelProber.ProbeResult result = prober.probe(
            "openai", "http://127.0.0.1:11434", "sk-never-send", "model");

        assertFalse(result.success());
        assertEquals(0, calls.get());
    }

    @Test
    void redirectResponseShouldBeTerminalAndClientRedirectsAreDisabled() {
        AtomicInteger calls = new AtomicInteger();
        Interceptor redirect = chain -> {
            calls.incrementAndGet();
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "http://127.0.0.1/latest/meta-data")
                .body(ResponseBody.create("redirect", JSON))
                .build();
        };
        ChatModelProber prober = new ChatModelProber(Duration.ofSeconds(1), strictPolicy(), redirect);

        ChatModelProber.ProbeResult result = prober.probe(
            "openai", PUBLIC_BASE_URL, "sk-test", "model");

        assertFalse(result.success());
        assertTrue(result.message().contains("302"));
        assertEquals(1, calls.get());
        assertFalse(prober.followsRedirects());
    }

    private ChatModelProber prober(int status, String body, AtomicReference<Request> captured) {
        Interceptor interceptor = chain -> {
            if (captured != null) {
                captured.set(chain.request());
            }
            return response(chain.request(), status, body);
        };
        return new ChatModelProber(Duration.ofSeconds(1), strictPolicy(), interceptor);
    }

    private Response response(Request request, int status, String body) {
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(ResponseBody.create(body, JSON))
            .build();
    }

    private ModelEndpointPolicy strictPolicy() {
        return new ModelEndpointPolicy(List::of);
    }
}
