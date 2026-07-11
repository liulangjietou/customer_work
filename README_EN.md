# customer-work · Production-grade AI Customer-Service System on AgentScope Java

[![CI](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml/badge.svg)](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#requirements)

> 中文版：[README.md](README.md)

> 🆕 **AgentScope 2.0.0 GA**: the `main` branch has fully migrated to
> `io.agentscope:agentscope-harness:2.0.0` (the GA release, published 2026-07-10 after 5 RC iterations) and adds
> the 2.0 capabilities (Permission System, Plan Mode, Compaction, Workspace/Sandbox, Subagent, and the 5-stage
> Middleware that replaces v1 hooks). Historical snapshots: the `legacy-main-1.0.12` tag preserves `main`'s last
> state on **AgentScope Java 1.0.12** before this upgrade; the `rc2.0` branch (2.0.0-RC4) is a frozen snapshot of
> the first 1.x→2.0 migration pass. The `ga2.0` branch (the former parallel 2.0 migration branch) has been merged
> into `main` — do new work on `main`. See **[docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)** for the mapping, API changes and non-migratable
> features. The companion frontends live in the **`customer-web`** module, wiring the customer-service agent to
> five official surfaces — **admin** console, **chat-completions-web** (OpenAI-compatible `/v1/chat/completions`
> + a built-in chat page), **AG-UI** (`/agui/run` rich event protocol), **Studio** observability, and
> **Channel · DingTalk** (stream-mode bot integration) — see
> **[docs/customer-web操作文档.md](docs/customer-web操作文档.md)**.

A production-grade reference implementation of an AI customer-service agent. The `main` branch is built on
**`io.agentscope:agentscope-harness:2.0.0`** (GA release), defaulting to **Alibaba Cloud Bailian (DashScope / Qwen)**.

Every capability is **"a config switch + a replaceable implementation"**: built-in in-process
implementations make it run offline out of the box and keep the test suite green, while a single
config line swaps to a cloud / self-hosted backend — **without touching business code**.

- Base package: `com.richard.fyoung.customerwork`
- **511 unit tests** on `main` (AgentScope 2.0.0 GA): starter 362 + app 13 + downstream 1 + customer-web 8 + the standalone admin console `customer-admin-server` 127 (several starter integration tests auto-skip when their external service, Bailian / Redis / MySQL / Nacos, is absent); the pre-upgrade AgentScope 1.0.12 state (176 unit tests) is preserved under the `legacy-main-1.0.12` tag

## Feature overview

> This table reflects the **`main` branch (AgentScope 2.0.0 GA)**. Per-feature 1.x→2.0 API mapping is in
> **[docs/MIGRATION-2.0.md](docs/MIGRATION-2.0.md)**.

| Capability | Class | Default | Enable |
|---|---|---|---|
| Agent (ReAct / Harness) | `CustomerServiceAgentFactory` / `HarnessAgentFactory` | on | — |
| Streaming events (SSE / `streamEvents`) | controller `chatStream` | on | `POST /chat/stream` |
| Structured output | `classifyIntent` | on | `POST /intent` |
| **Session/state persistence (AgentStateStore)** | `SessionConfig` | on | `session.mode=memory/json/redis/mysql` (replaces v1 Session) |
| Long-term memory (multi-tenant) | `LongTermMemoryProvider` | on | `memory.provider=memory/bailian/mem0/reme` |
| Three-tier memory + fact log (**file rotation**) | `FactLog` | on | `fact-log.enabled`, `fact-log.max-file-mb` |
| **Context compression (Compaction)** | `ContextMemoryFactory`→`CompactionConfig` | off | `context.compression-enabled` (replaces v1 AutoContext) |
| RAG | `KnowledgeProvider` | on | `rag.provider=memory/simple/bailian/dify` |
| Business tools — 7 Tool Groups (presale/order/after-sales/member/complaint/knowledge/human) | `ToolRegistrar` + `*Backend` SPIs | on | `agent.meta-tool-enabled` |
| **Pre-sale guide** | `ProductTools` / `ProductBackend` | on | `queryProduct`/`recommendProducts`/`checkStock`/`queryPromotions` |
| **In-sale** | `OrderTools` (default-method evolution) | on | `modifyAddress`/`cancelOrder`/`urgeShipment` |
| **After-sales (full)** | `AfterSalesTools` / `AfterSalesBackend` | on | refund/return/exchange/price-protection/invoice + `queryRefundProgress` |
| **Member / account** | `MemberTools` / `MemberBackend` | on | `queryPoints`/`queryMemberLevel`/`resolveAccountIssue` |
| **Complaint ticket** | `ComplaintTools` / `ComplaintBackend` | on | `fileComplaint`/`queryComplaint` |
| Skill + **self-evolution (SkillCurator)** | `SkillBox` / `enableSkillCurator` | on/off | `skill.*` / `harness.skill-curator-enabled` |
| **Multi-agent orchestration (Reactor)** — true-parallel fanout + rule fast-lane routing + reduce | `MultiAgentOrchestrator` | on | `POST /consult`; `multi-agent.fast-route-enabled`/`routing-enabled`/`reduce-enabled` (replaces v1 Pipelines) |
| **Human approval loop + persistence SPI + timeout patrol** | `PendingApprovalService` + `ApprovalController` + `ApprovalStore` SPI + `ApprovalTimeoutScheduler` | on | `GET /approvals` · `POST /approvals/{id}/approve\|deny` · `human-approval.timeout-seconds` |
| **Multi-turn slot filling** | `SlotFillingService` + `RefundFormController` | on | `POST /forms/refund` |
| **Proactive service (notify/survey, reuses Channel push)** | `ProactiveNotificationService` + `NotificationChannel` | on | `POST /notify/order-status\|survey` |
| **Agent-assist + quality inspection** | `AgentAssistService` + `QualityInspectionService` | on | `POST /assist` · `POST /quality/inspect` |
| **Dialog-stage state machine (dynamic prompt)** | `DialogStageMiddleware` + `DialogStageService` | on | onSystemPrompt stage injection |
| **Automated intent eval** | `IntentEvalRunner` + `eval/intent-eval-cases.json` | on | accuracy/coverage baseline |
| **5-stage Middleware** | `middleware/*Middleware` (Observability/Audit/Latency/Masking/ToolGuard/DynamicOptions/SelfCorrection/HumanApproval/TenantContext/DialogStage) | on/off | declare a `MiddlewareBase` bean (replaces v1 Hook) |
| **Permission system (3-state)** | `PermissionConfig` | off | `harness.permission.enabled` |
| **Plan Mode** | `HarnessAgentFactory` | off | `harness.plan-mode.enabled` |
| **Workspace / Sandbox** | `HarnessAgentFactory#applySandbox` | off | `harness.sandbox.mode=local/docker` |
| **Subagent** | `HarnessAgentFactory` | off | `harness.subagent.enabled` |
| Human-in-the-loop + interrupt | `HumanApprovalMiddleware` + Permission ask | on | `POST /session/{id}/interrupt` |
| Observability + metrics + tracing | `ObservabilityMiddleware` / `TracingConfig` | on/off | `/actuator/prometheus` |
| Multi-vendor model + fallback/retry/**cost circuit breaker** | `ModelConfig` (or v2 built-in `maxRetries`/`fallbackModel`) + `ModelCostCircuitBreaker` | on | `model.provider`, `model.fallback.enabled`, `model.cost-control.enabled` |
| MCP / Higress / Nacos | configurers | off | `mcp.*` / `higress.*` / `nacos.*` |
| API-key auth + **sliding-window rate limit** | `ApiKeyAuthWebFilter` / `RateLimitWebFilter` (fixed & sliding-window) | off | `security.auth.enabled` / `security.rate-limit.enabled` |
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

## Production hardening (P0–P3)

The `main` branch includes a full production-hardening pass (P0–P3). Key items:

### P0 — Production-critical
- **Approval store SPI**: `PendingApprovalService` storage extracted to `ApprovalStore` interface + `InMemoryApprovalStore` default (`@ConditionalOnMissingBean`). Downstream can declare a JDBC/Redis implementation to persist approval tickets across restarts — critical for refund approvals involving money.
- **Session-level concurrency control**: `CustomerServiceService` uses a `Semaphore(1)` per `sessionId` to serialize concurrent requests to the same session, preventing concurrent writes to `AgentStateStore` from causing state overwrites. Lock acquisition happens on `boundedElastic`, not blocking the Netty event loop.

### P1 — Architectural robustness
- **SlotFilling store SPI**: `SlotFillingService` storage extracted to `SlotFillingStore` interface + `InMemorySlotFillingStore` default. Progress survives restarts when a JDBC/Redis implementation is provided.
- **Approval timeout patrol**: `ApprovalTimeoutScheduler` (`@Scheduled`) periodically scans PENDING approvals; those exceeding `human-approval.timeout-seconds` are auto-processed per `timeout-action` (`escalate` or `deny`). Disabled by default.
- **Session idle cleanup**: `SessionTimeoutScheduler` periodically cleans up sessions idle for longer than `session.idle-timeout-minutes` (removes hot agent cache + persisted state + session lock). Disabled by default.

### P2 — Robustness continued
- **Sliding-window rate limit**: `RateLimitWebFilter` supports `algorithm=sliding-window` (uses `ArrayDeque<Long>` timestamps, evicts expired) in addition to the default fixed-window, avoiding boundary 2× burst.
- **Model cost circuit breaker**: `ModelCostCircuitBreaker` tracks token consumption per minute/hour window; `tryConsume(int)` atomically checks + rolls back, `isCircuitOpen()` reports status. Config: `model.cost-control.enabled` / `max-tokens-per-minute` / `max-tokens-per-hour`.
- **FactLog file rotation**: `FactLog` rotates when file size exceeds `max-file-mb`, archiving to `.1`/`.2`; oldest archives beyond `max-archived-files` are auto-deleted.
- **Jacoco coverage gate**: starter POM adds `check-coverage` execution — line ≥ 50%, branch ≥ 40%.

### P3 — Feature completeness + engineering quality
- **JDBC audit sink**: `JdbcAuditSink` writes audit events to `cw_audit_log` table (auto-creates table). Downstream declares `DataSource` + `JdbcAuditSink` bean to override the default `LoggingAuditSink`.
- **LLM-as-Judge quality eval**: `QualityEvalRunner` uses an LLM to score agent replies (1–5) on relevance, accuracy, and completeness. Complements `IntentEvalRunner` (offline deterministic). Uses `JudgeModel` functional interface for decoupling.
- **Multi-agent specialist cache**: `MultiAgentOrchestrator.buildSpecialists()` uses double-check locking + volatile cache; `clearSpecialistCache()` supports hot-reload.
- **Skill version management**: `SkillVersionManager` parses `<!-- version: x.y.z -->` from skill Markdown, tracks loaded versions, and `checkUpdates()` detects version changes for hot-reload.

## HTTP endpoints

`POST /api/customer/chat` · `/chat/stream` · `/intent` · `/consult` · `/agui` ·
`POST /session/{id}/interrupt` · `DELETE /session/{id}` · `GET /health` ·
`GET /approvals` · `POST /approvals/{id}/approve|deny` (human approval) ·
`POST /forms/refund` (slot filling) ·
`POST /notify/order-status|survey` (proactive service) ·
`POST /assist` · `POST /quality/inspect` (agent-assist + quality) ·
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
- `customer-work-app` — runnable customer-service application (package `com.richard.fyoung.customerworkapp`).

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
**Secrets are never committed** — see `.env.example`.

| Prefix | Key items (defaults) |
|---|---|
| `model` | provider(dashscope), name(qwen-max), api-key(${DASHSCOPE_API_KEY}), temperature(0.3), max-tokens(1500), stream(true), top-p, reasoning-effort, enable-search, enable-thinking, embedding-name(text-embedding-v3), token-warn-threshold(0), fallback.*, retry.*, cost-control.{enabled(false),max-tokens-per-minute(100000),max-tokens-per-hour(1000000)} |
| `session` | mode(memory), directory, idle-timeout-minutes(0), redis.*, mysql.* |
| `agent` | max-iters(10), meta-tool-enabled(false) |
| `memory` | long-term-enabled(true), provider(memory), tenant-delimiter(":"), retrieve-top-k(5), bailian.*/mem0.*/reme.* |
| `plan` | enabled(true), max-subtasks(20) |
| `rag` | enabled(true), provider(memory), top-k(3), simple.dimensions(1024), bailian.*/dify.* |
| `context` | compression-enabled(false), max-token(8000), msg-threshold(40), last-keep(10) |
| `skill` | enabled(true), repository(classpath), location(skills), directory, writable(true), runtime-load-tool-enabled(false), code-execution-enabled(false) |
| `mcp` | enabled(false), servers[] |
| `observability` | trace-enabled(false), trace-file, tracing-enabled(false), studio.* |
| `human-approval` | enabled(true), guarded-tools([submitRefund]), timeout-seconds(0), timeout-action(escalate), store-mode(memory) |
| `fact-log` | enabled(true), directory(./data/facts), max-file-mb(10), max-archived-files(5) |
| `security` | auth.{enabled(false),header-name(X-API-Key),api-keys[]}, rate-limit.{enabled(false),requests-per-minute(120),algorithm(fixed-window),window-seconds(60)} |
| `multi-agent` | enabled(true), mode(fanout), max-iters(6) |
| `runtime` | shutdown-timeout-seconds(30), scheduler-enabled(false), scheduler-fixed-delay-ms(60000) |
| `interrupt` | pending-tool-recovery-enabled(true) |
| `nacos` | enabled(false), server-addr(localhost:8848), namespace, group(DEFAULT_GROUP), prompt-data-id, username, password, timeout-ms(3000) |
| `higress` | enabled(false), name(higress), endpoint, transport(sse), tool-search, max-tools(10), timeout-seconds(30) |
| `protocol` | agui.{enabled(true),enable-reasoning(true),emit-tool-call-args(true)}, tts.enabled(false) |
| `stream` | idle-timeout-seconds(120) (SSE idle timeout; `<=0` disables, mitigates framework #1741) |
| `hooks.tool-guard` | enabled(false), inject-params, numeric-caps, destructive-patterns(rm -rf / .agentscope/workspace / del /[fs] / format) |

## Testing

```bash
mvn test                                 # all unit tests, green offline
mvn test -Dtest=BailianIntegrationTest   # real Bailian call (needs RUN_BAILIAN_IT=true)
```

**259 tests** in the starter (0 failures, 0 errors; 4 integration tests auto-skip when external services are absent).
Redis/MySQL/Nacos integration tests run when the service is reachable and auto-skip otherwise.
CI (`.github/workflows/ci.yml`) spins up Redis/MySQL service containers so persistence tests run for real.

See [CHANGELOG.md](CHANGELOG.md) for the full P0–P3 hardening changelog.

## License

[Apache-2.0](LICENSE) · See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## Follow the Author

If you are interested in AI and this project, follow my WeChat official account
**AI赛博炼丹炉** for more high-quality articles and hands-on content.

<p align="center">
  <img src="docs/assets/wechat-qr.png" alt="WeChat official account: AI赛博炼丹炉" width="420">
</p>
