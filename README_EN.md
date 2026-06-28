# customer-work · Production-grade AI Customer-Service System on AgentScope Java

[![CI](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml/badge.svg)](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)

> 中文版：[README.md](README.md)

> 🆕 **AgentScope 2.0 migration (`rc2.0` branch)**: the `rc2.0` branch migrates everything to
> `io.agentscope:agentscope-harness:2.0.0-RC4` and adds the 2.0 capabilities (Permission System,
> Plan Mode, Compaction, Workspace/Sandbox, Subagent, and the 5-stage Middleware that replaces v1 hooks).
> See **[docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)** for the mapping, API changes and non-migratable
> features. The companion frontends live in the **`customer-web`** module, wiring the customer-service agent to
> five official surfaces — **admin** console, **chat-completions-web** (OpenAI-compatible `/v1/chat/completions`
> + a built-in chat page), **AG-UI** (`/agui/run` rich event protocol), **Studio** observability, and
> **Channel · DingTalk** (stream-mode bot integration) — see
> **[docs/customer-web操作文档.md](docs/customer-web操作文档.md)**. The `main` branch stays on the stable 1.0.12 release.

A production-grade reference implementation of an AI customer-service agent. The `main` branch is built on
**AgentScope Java 1.0.12**, defaulting to **Alibaba Cloud Bailian (DashScope / Qwen)**.

Every capability is **"a config switch + a replaceable implementation"**: built-in in-process
implementations make it run offline out of the box and keep the test suite green, while a single
config line swaps to a cloud / self-hosted backend — **without touching business code**.

- Base package: `com.richard.fyoung.customerwork`
- **176 unit tests** (4 auto-skip when their external service, Bailian / Redis / MySQL / Nacos, is absent)

## Feature overview

> This table reflects the **`rc2.0` branch (AgentScope 2.0.0-RC4)**. Per-feature 1.x→2.0 API mapping is in
> **[docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)**.

| Capability | Class | Default | Enable |
|---|---|---|---|
| Agent (ReAct / Harness) | `CustomerServiceAgentFactory` / `HarnessAgentFactory` | on | — |
| Streaming events (SSE / `streamEvents`) | controller `chatStream` | on | `POST /chat/stream` |
| Structured output | `classifyIntent` | on | `POST /intent` |
| **Session/state persistence (AgentStateStore)** | `SessionConfig` | on | `session.mode=memory/json/redis/mysql` (replaces v1 Session) |
| Long-term memory (multi-tenant) | `LongTermMemoryProvider` | on | `memory.provider=memory/bailian/mem0/reme` |
| Three-tier memory + fact log | `FactLog` | on | `fact-log.enabled` |
| **Context compression (Compaction)** | `ContextMemoryFactory`→`CompactionConfig` | off | `context.compression-enabled` (replaces v1 AutoContext) |
| RAG | `KnowledgeProvider` | on | `rag.provider=memory/simple/bailian/dify` |
| Tools + Tool Group + Meta-Tool | `ToolRegistrar` | on | `agent.meta-tool-enabled` |
| Skill + **self-evolution (SkillCurator)** | `SkillBox` / `enableSkillCurator` | on/off | `skill.*` / `harness.skill-curator-enabled` |
| **Multi-agent orchestration (Reactor)** | `MultiAgentOrchestrator` | on | `POST /consult` (replaces v1 Pipelines) |
| **5-stage Middleware** | `middleware/*Middleware` (Observability/Audit/Latency/Masking/ToolGuard/DynamicOptions/SelfCorrection/HumanApproval/TenantContext/DialogStage) | on/off | declare a `MiddlewareBase` bean (replaces v1 Hook) |
| **Permission system (3-state)** | `PermissionConfig` | off | `harness.permission.enabled` |
| **Plan Mode** | `HarnessAgentFactory` | off | `harness.plan-mode.enabled` |
| **Workspace / Sandbox** | `HarnessAgentFactory#applySandbox` | off | `harness.sandbox.mode=local/docker` |
| **Subagent** | `HarnessAgentFactory` | off | `harness.subagent.enabled` |
| Human-in-the-loop + interrupt | `HumanApprovalMiddleware` + Permission ask | on | `POST /session/{id}/interrupt` |
| Observability + metrics + tracing | `ObservabilityMiddleware` / `TracingConfig` | on/off | `/actuator/prometheus` |
| Multi-vendor model + fallback/retry | `ModelConfig` (or v2 built-in `maxRetries`/`fallbackModel`) | on | `model.provider`, `model.fallback.enabled` |
| MCP / Higress / Nacos | configurers | off | `mcp.*` / `higress.*` / `nacos.*` |
| API-key auth + rate limit | web filters | off | `security.*` |
| **Companion frontends (`customer-web`)** | admin / chat-completions / AG-UI / Studio / Channel (DingTalk·Feishu·WeCom) | off | see [docs/customer-web操作文档.md](docs/customer-web操作文档.md) |

### ⚠️ Non-migratable / carry-over notes (v1 → v2)

> The following were **removed or restructured by AgentScope 2.0** and cannot migrate 1:1; handled the v2-recommended way or kept as documented placeholders. See [docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md).

| v1 capability | v2 disposition | Note |
|---|---|---|
| **Realtime TTS** (`TTSHook` / `DashScopeRealtimeTTSModel`) | ❌ removed from core | `TtsHookProvider` degraded to a documented no-op; integrate a vendor realtime-TTS SDK at the gateway/front-end |
| **PlanNotebook** (`core.plan.*`) | 🔁 replaced by **Plan Mode** | semantics shift from "structured subtask store" to "read-only plan + approve-then-write" (not equivalent) |
| **Pipelines** (`core.pipeline.Pipelines`) | 🔁 replaced by **Reactor / Subagent** | removed; `MultiAgentOrchestrator` uses Reactor, or use HarnessAgent Subagent |
| **SessionManager / StateModule** | ❌ removed | agents are stateless in v2; framework auto-manages state by `(userId,sessionId)`; `SessionStateManager` is now an `AgentStateStore` admin facade |
| **legacy Hook API** (`core.hook.Hook`) | 🔁 replaced by **5-stage Middleware** | 8 business hooks migrated to `MiddlewareBase`; `gotoReasoning`/`stopAgent` have no middleware equivalent → hard constraints enforced by **Permission ask/deny**; `GlobalHookRegistry` still uses `AgentBase.addSystemHook` (only v2 API for system hooks, deprecated-for-removal) |

**Extension points (need extra infra/SDK):** remote sandbox (k8s/e2b/daytona/agentrun; local/docker built-in),
Channel·GitHub/GitLab (DingTalk/Feishu/WeCom done), A2A registry (Nacos AI API + `io.a2a`), RocketMQ,
Quartz/XXL-JOB scheduling, Training (RM Gallery/Trinity), Anthropic/Gemini SDKs, RAGFlow/Haystack.

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

## Modules & use as a dependency

Multi-module Maven project:
- `customer-work-spring-boot-starter` — reusable agent infrastructure, **auto-configured** via
  `@AutoConfiguration` (registered in `META-INF/spring/...AutoConfiguration.imports`).
- `customer-work-example` — runnable customer-service demo (package `com.richard.fyoung.customerworkapp`).

Any downstream app just adds the starter dependency — **no base-package assumptions, no manual `@ComponentScan`**:

```xml
<dependency>
  <groupId>io.github.richardfyoung</groupId>
  <artifactId>customer-work-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
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

## Follow the Author

If you are interested in AI and this project, follow my WeChat official account
**AI赛博炼丹炉** for more high-quality articles and hands-on content.

<p align="center">
  <img src="docs/assets/wechat-qr.png" alt="WeChat official account: AI赛博炼丹炉" width="420">
</p>
