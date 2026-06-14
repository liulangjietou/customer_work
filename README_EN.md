# customer-work · Production-grade AI Customer-Service System on AgentScope Java

[![CI](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml/badge.svg)](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)

> 中文版：[README.md](README.md)

A production-grade reference implementation of an AI customer-service agent built on
**AgentScope Java 1.0.12**, defaulting to **Alibaba Cloud Bailian (DashScope / Qwen)**.

Every capability is **"a config switch + a replaceable implementation"**: built-in in-process
implementations make it run offline out of the box and keep the test suite green, while a single
config line swaps to a cloud / self-hosted backend — **without touching business code**.

- Base package: `com.richard.fyoung.customerwork`
- **116 unit tests** (4 auto-skip when their external service — Bailian / Redis / MySQL / Nacos — is absent)

## Feature overview

| Capability | Class | Default | Enable |
|---|---|---|---|
| ReAct agent | `CustomerServiceAgentFactory` | on | — |
| Streaming (SSE) | controller `chatStream` | on | `POST /chat/stream` |
| Structured output | `classifyIntent` | on | `POST /intent` |
| Sessions & persistence | `SessionConfig` | on | `session.mode=memory/json/redis/mysql` |
| Long-term memory (multi-tenant) | `LongTermMemoryProvider` | on | `memory.provider=memory/bailian/mem0/reme` |
| Three-tier memory + fact log | `FactLog` | on | `fact-log.enabled` |
| Context compression | `ContextMemoryFactory` (AutoContext) | off | `context.compression-enabled` |
| RAG | `KnowledgeProvider` | on | `rag.provider=memory/simple/bailian/dify` |
| Tools + Tool Group + Meta-Tool | factory | on | `agent.meta-tool-enabled` |
| Skill (classpath/filesystem/code-exec) | `SkillBox` | on | `skill.*` |
| Multi-agent orchestration | `MultiAgentOrchestrator` | on | `POST /consult` |
| Human-in-the-loop + interrupt | `HumanApprovalHook` | on | `POST /session/{id}/interrupt` |
| Observability + metrics + tracing | `ObservabilityHook` / `LoggingTracer` | on/off | `/actuator/prometheus` |
| Multi-vendor model + fallback | `ModelConfig` / `FallbackChatModel` | on | `model.provider`, `model.fallback.enabled` |
| AG-UI protocol | `AguiService` | on | `POST /agui` |
| TTS | `TtsHookProvider` | off | `protocol.tts.enabled` |
| MCP / Higress / Studio | configurers | off | `mcp.*` / `higress.*` / `observability.studio.*` |
| API-key auth + rate limit | web filters | off | `security.*` |
| Nacos config center (prompt hot-reload) | `NacosPromptService` | off | `nacos.enabled` |

**Extension points (need extra infra/SDK):** A2A registry (Nacos AI API + `io.a2a` SDK), RocketMQ,
Quartz/XXL-JOB scheduling, Runtime sandbox, Training (RM Gallery/Trinity), Anthropic/Gemini SDKs,
RAGFlow/Haystack, Harness (1.1+).

## HTTP endpoints

`POST /api/customer/chat` · `/chat/stream` · `/intent` · `/consult` · `/agui` ·
`POST /session/{id}/interrupt` · `DELETE /session/{id}` · `GET /health` ·
Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs`.

## Requirements

- JDK 17+ (verified on 21), Maven 3.8+
- A DashScope (Bailian) API key
- Optional: Redis / MySQL / Nacos / Higress

## Quick start

```bash
cp .env.example .env          # fill in DASHSCOPE_API_KEY
export DASHSCOPE_API_KEY=sk-your-key
mvn spring-boot:run
# or one-command deps + app:
docker compose up -d          # redis + mysql + nacos
```

```bash
curl -X POST http://localhost:8080/api/customer/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"Where is my order 20260613001?"}'
```

## Make it your own business agent

Business tools delegate to interfaces under `com.richard.fyoung.customerwork.tool.backend`
(`OrderBackend`, `AfterSalesBackend`, `KnowledgeBackend`). Default mock implementations are
annotated `@ConditionalOnMissingBean`, so you just provide your own `@Bean` (calling your real
microservices / DB / RAG) to take over — no framework changes:

```java
@Component
public class MyOrderBackend implements OrderBackend {
    public Mono<String> queryOrder(String orderId) { /* call your order service */ }
    public Mono<String> queryLogistics(String orderId) { /* ... */ }
}
```

Also customizable: system prompt (`SYSTEM_PROMPT` or via Nacos hot-reload), tool groups, RAG docs,
skills under `resources/skills/<name>/SKILL.md`.

## Configuration

All under `customer-work.*` in `application.yml`, overridable by environment variables.
**Secrets are never committed** — see `.env.example`. Full table is in the Chinese README §7.

## Testing

```bash
mvn test                                 # all unit tests, green offline
mvn test -Dtest=BailianIntegrationTest   # real Bailian call (needs RUN_BAILIAN_IT=true)
```
Redis/MySQL/Nacos integration tests run when the service is reachable and auto-skip otherwise.
CI (`.github/workflows/ci.yml`) spins up Redis/MySQL service containers so persistence tests run for real.

## License

[Apache-2.0](LICENSE) · See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).
