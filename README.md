# customer-work · 基于 AgentScope Java 的生产级智能客服系统

[![CI](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml/badge.svg)](https://github.com/liulangjietou/customer_work/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](#四环境要求)
[![AgentScope](https://img.shields.io/badge/AgentScope-1.0.12-green.svg)](https://github.com/agentscope-ai/agentscope-java)

> 🚀 **新人从这里开始**：[docs/新人必读.md](docs/新人必读.md)（15 分钟跑起来 + 看懂结构 + 知道改哪里）
> English version: [README_EN.md](README_EN.md)
> 详细技术文档（原理 / 架构图 / 时序图 / UML 类图 / 扩展点）：[docs/详细技术文档.md](docs/详细技术文档.md)

本项目是配套文章《AgentScope Java 生产实践深度解析》中那张客服业务流程图的**生产级代码实现**，
基于官方稳定版坐标 `io.agentscope:agentscope:1.0.12`，默认对接**阿里云百炼（DashScope / 通义千问）**。

> 开源说明：本项目以**可改造为你自己的业务 Agent** 为目标——业务工具走 `tool.backend.*` 接口，
> 你只需实现接口（或覆盖 Bean）即可接入自有订单/售后/知识系统，无需改框架代码。详见
> [§6.9 把它改成你自己的业务 Agent](#69-工具集成--把它改成你自己的业务-agent)。

- 包名：`com.richard.fyoung.customerwork`
- 单元测试：**115 个全绿**（其中 3 个按外部服务可用性自动跳过：百炼 / Redis / MySQL）
- 设计原则：**每个能力都是「配置开关 + 可替换实现」**——内置进程内实现保证开箱即用与可单测，生产可一行配置切到云端 / 私有化后端，业务代码零改动。

---

## 目录

1. [流程图映射](#一流程图映射)
2. [功能总表（已实现 + 未实现）](#二功能总表)
3. [HTTP 接口速查](#三http-接口速查)
4. [环境要求](#四环境要求)
5. [快速开始](#五快速开始)
6. [各功能用法与测试](#六各功能用法与测试)
7. [配置项总表](#七配置项总表)
8. [测试说明](#八测试说明)
9. [代码结构](#九代码结构)
10. [未实现 / 需外部基础设施的扩展点](#十未实现--需外部基础设施的扩展点)
11. [重要说明](#十一重要说明)

---

## 一、流程图映射

| 流程图阶段 | 实现 | 说明 |
|---|---|---|
| ② 会话恢复与上下文装配 | ✅ | `CustomerServiceService` 按 sessionId 维护 Agent，框架 `Session/State` 持久化恢复（memory/json/redis/mysql） |
| ③ 主 Agent 意图识别与路由 | ✅ | `CustomerServiceAgentFactory` 用 `ReActAgent` + 系统提示词实现意图理解、工具路由、高风险熔断 |
| ④ 子能力分层执行 | ✅ | 四个 Tool Group（知识库/订单/售后/人工）+ 涉资金人工确认 + 多 Agent 编排 |
| ⑤ 观察-再推理循环 → 回复 | ✅ | `ReActAgent` 内置 ReAct 循环；SSE 订阅 `agent.stream()` 逐片段下发 |
| ① 接入与流量治理 | ✅/⚠️ | 鉴权限流 ✅；Higress ✅(可选)；RocketMQ ⚠️ 扩展点 |
| ⑥ 数据飞轮 | ✅/⚠️ | 可观测/Tracing/指标 ✅；RM Gallery / Trinity-RFT ⚠️ 扩展点 |

---

## 二、功能总表

> 「默认」列：开=随应用启动生效；关=需配置开启；⚠️=需外部基础设施/SDK 的扩展点。

### 已实现（开箱即用 / 配置开启）

| 功能 | 核心类 | 默认 | 开启方式 |
|---|---|---|---|
| ReActAgent 推理 | `CustomerServiceAgentFactory` | 开 | — |
| 流式输出（SSE） | `CustomerServiceController#chatStream` | 开 | `POST /chat/stream` |
| 结构化输出（意图） | `CustomerServiceService#classifyIntent` | 开 | `POST /intent` |
| 多轮会话 & 持久化 | `SessionConfig` | 开 | `session.mode=memory/json/redis/mysql` |
| 状态自动编排 | `SessionStateManager` | 开 | 注入使用 |
| 长期记忆（多租户） | `LongTermMemoryProvider` | 开 | `memory.provider=memory/bailian/mem0/reme` |
| 三层记忆 + 事实日志 | `InMemoryLongTermMemory` + `FactLog` | 开 | `fact-log.enabled` |
| 智能上下文压缩 | `ContextMemoryFactory`(AutoContext) | 关 | `context.compression-enabled=true` |
| RAG 知识检索 | `KnowledgeProvider` | 开 | `rag.provider=memory/simple/bailian/dify` |
| 工具集成 + Tool Group | `CustomerServiceAgentFactory#buildToolkit` | 开 | — |
| Meta-Tool 元工具 | 同上 | 关 | `agent.meta-tool-enabled=true` |
| Skill 技能库 | `SkillBox` 装配 | 开 | `skill.repository=classpath/filesystem` |
| Skill 运行时加载 / 代码执行 | 同上 | 关 | `skill.runtime-load-tool-enabled` / `skill.code-execution-enabled` |
| 多 Agent 编排 | `MultiAgentOrchestrator` | 开 | `POST /consult`，`multi-agent.mode=fanout/sequential` |
| Human-in-the-Loop | `HumanApprovalHook` | 开 | `human-approval.enabled` + `POST /session/{id}/interrupt` |
| 中断恢复 | `enablePendingToolRecovery` | 开 | `interrupt.pending-tool-recovery-enabled` |
| 可观测 Hook + 指标 | `ObservabilityHook` + Micrometer | 开 | `/actuator/prometheus` |
| 原生 Tracing | `LoggingTracer` + `TracerRegistry` | 关 | `observability.tracing-enabled=true` |
| 运维就绪（健康/停机/巡检） | `SessionHealthIndicator` / `GracefulShutdownService` / `MaintenanceScheduler` | 开 | `/actuator/health` |
| 模型多厂商 + 私有化兜底 | `ModelConfig` + `FallbackChatModel` | 开 | `model.provider`、`model.fallback.enabled` |
| AG-UI 协议 | `AguiService` | 开 | `POST /agui` |
| TTS 语音合成 | `TtsHookProvider` | 关 | `protocol.tts.enabled=true` |
| MCP 接入 | `McpToolkitConfigurer` | 关 | `mcp.enabled=true` |
| Higress AI 网关 | `HigressToolkitConfigurer` | 关 | `higress.enabled=true` |
| Studio 可视化 | `StudioConfigurer` | 关 | `observability.studio.enabled=true` |
| 接入层安全（鉴权/限流） | `ApiKeyAuthWebFilter` / `RateLimitWebFilter` | 关 | `security.auth.enabled` / `security.rate-limit.enabled` |
| Nacos 配置中心（提示词热更新） | `NacosPromptService` | 关 | `nacos.enabled=true` |

### 未实现 / 需外部基础设施（扩展点，见 [第十节](#十未实现--需外部基础设施的扩展点)）

| 功能 | 状态 | 需要 |
|---|---|---|
| A2A Agent Card 注册发现 | ⚠️ | Nacos AI API（新版 nacos-client）+ `io.a2a` SDK |
| RocketMQ 异步消息 | ⚠️ | RocketMQ broker + client |
| 定时 Agent 调度 | ⚠️ | Quartz / XXL-JOB |
| Runtime 工具沙箱 | ⚠️ | 独立项目 `agentscope-runtime-java` |
| Training 数据飞轮 | ⚠️ | RM Gallery + Trinity-RFT 平台 |
| Anthropic / Gemini 模型 | ⚠️ | 各自厂商 SDK 依赖 |
| RAGFlow / Haystack 知识库 | ⚠️ | 同 Dify 模式，按需补依赖 |
| Harness 长任务脚手架 | ⚠️ | AgentScope 1.1+（1.0.12 暂无） |

---

## 三、HTTP 接口速查

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/customer/chat` | 同步对话，返回完整回复 |
| POST | `/api/customer/chat/stream` | 流式对话（SSE，逐片段） |
| POST | `/api/customer/intent` | 结构化意图识别（强类型 JSON） |
| POST | `/api/customer/consult` | 多 Agent 协作咨询（多专家聚合） |
| POST | `/api/customer/agui` | AG-UI 标准事件流（SSE） |
| POST | `/api/customer/session/{id}/interrupt` | 安全中断会话 |
| DELETE | `/api/customer/session/{id}` | 结束并清理会话 |
| GET | `/api/customer/health` | 健康检查 |
| GET | `/actuator/health` `/metrics` `/prometheus` | 运维端点 |
| GET | `/swagger-ui.html` | Swagger UI 交互式 API 文档 |
| GET | `/v3/api-docs` | OpenAPI JSON |

> 启动后打开 **http://localhost:8080/swagger-ui.html** 即可在线查看 / 调试全部接口（Swagger / springdoc-openapi）。鉴权开启时，Swagger 与 `/v3/api-docs`、`/webjars/**` 均免鉴权。

---

## 四、环境要求

- JDK 17+（本仓库用 JDK 21 验证通过）
- Maven 3.8+
- 一个阿里云百炼（DashScope）API Key
- 可选：Redis（密码 123456）、MySQL（root/root，库 `agent_scope_customer_work`）、Nacos、Higress 等

---

## 五、快速开始

**方式一：本地运行（默认内存模式，零依赖）**
```bash
cp .env.example .env                       # 填入 DASHSCOPE_API_KEY
export DASHSCOPE_API_KEY=你的百炼密钥        # 必填，密钥仅从环境变量读取
mvn spring-boot:run
```

**方式二：Docker Compose 一键起（app + Redis + MySQL + Nacos）**
```bash
export DASHSCOPE_API_KEY=你的百炼密钥
docker compose up -d                       # 全部起；或仅起依赖：docker compose up -d redis mysql nacos
```

```bash
# 同步对话
curl -X POST http://localhost:8080/api/customer/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"帮我查一下订单 20260613001 的状态和物流"}'
```

> 生产 profile：`SPRING_PROFILES_ACTIVE=prod`（见 `application-prod.yml`，env 驱动 redis/mysql/鉴权/限流/Tracing）。
> 测试覆盖率报告：`mvn test` 后见 `target/site/jacoco/index.html`。

---

## 六、各功能用法与测试

> 约定：sessionId 写成 `租户ID:会话ID`（如 `u1001:conv-1`）时，同租户不同会话**共享长期记忆**，跨租户严格隔离。

### 6.1 对话：同步 / 流式 / 结构化意图 / 中断 / 结束

```bash
# 同步
curl -X POST localhost:8080/api/customer/chat \
  -H "Content-Type: application/json" -d '{"sessionId":"u1001","message":"你们支持七天无理由退货吗？"}'

# 流式（SSE，逐片段）
curl -N -X POST localhost:8080/api/customer/chat/stream \
  -H "Content-Type: application/json" -d '{"sessionId":"u1001","message":"查订单 20260613001"}'

# 结构化意图（返回 {intent, orderId, urgent, summary}）
curl -X POST localhost:8080/api/customer/intent \
  -H "Content-Type: application/json" -d '{"sessionId":"u1001","message":"这个订单我要退款"}'

# 安全中断 / 结束会话
curl -X POST localhost:8080/api/customer/session/u1001/interrupt
curl -X DELETE localhost:8080/api/customer/session/u1001
```
测试：`CustomerServiceServiceTest`、`CustomerServiceControllerTest`。

### 6.2 多 Agent 编排（订单/售后/知识库专家协作）

```yaml
customer-work.multi-agent: { enabled: true, mode: fanout }   # fanout 并行聚合 | sequential 串行细化
```
```bash
curl -X POST localhost:8080/api/customer/consult \
  -H "Content-Type: application/json" -d '{"message":"订单 20260613001 想退款，能开发票吗？"}'
```
测试：`MultiAgentOrchestratorTest`（用离线 `EchoAgent` 验证 Pipeline 编排与聚合）。

### 6.3 AG-UI 标准协议

```bash
curl -N -X POST localhost:8080/api/customer/agui \
  -H "Content-Type: application/json" -d '{"sessionId":"u1001","message":"你好"}'
```
返回 AG-UI 编码事件流，兼容 AG-UI 前端直接对接。测试：`AguiServiceTest`。

### 6.4 会话持久化（memory / json / redis / mysql）

```yaml
customer-work:
  session:
    mode: redis     # memory | json | redis | mysql
    redis: { host: localhost, port: 6379, password: "123456", key-prefix: customer-work }
    mysql: { host: localhost, port: 3306, database: agent_scope_customer_work, username: root, password: root, auto-create: true }
```
- MySQL 表 `agentscope_sessions` 自动建；手工脚本 `mysql/schema.sql`。
- 测试：`SessionConfigTest`（离线）、`RedisSessionPersistenceTest` / `MysqlSessionPersistenceTest`（服务可达才真实跑，不可达自动跳过）。

### 6.5 长期记忆（多租户，可切后端）

```yaml
customer-work.memory:
  provider: memory          # memory | bailian | mem0 | reme
  bailian: { api-key: "", memory-library-id: "", top-k: 5 }   # api-key 留空复用 model.api-key
  mem0:    { api-key: "", api-base-url: https://api.mem0.ai, api-type: platform }
  reme:    { api-base-url: "" }
```
测试：`LongTermMemoryTest`（记录/召回/租户隔离）、`LongTermMemoryProviderTest`（provider 选择）。

### 6.6 三层记忆体系 + 事实日志

L1 短期 `Memory` + L2 长期 `LongTermMemory` + L3 只追加 `FactLog`（JSONL，可审计）。
```yaml
customer-work.fact-log: { enabled: true, directory: ./data/facts }
```
测试：`FactLogTest`（追加/读取/租户隔离/禁用）。

### 6.7 智能上下文压缩（长对话上下文有界）

```yaml
customer-work.context: { compression-enabled: true, max-token: 8000, msg-threshold: 40, last-keep: 10 }
```
开启后短期记忆用 `AutoContextMemory`（自动压缩 + 大工具结果卸载）。测试：`ContextMemoryFactoryTest`。

### 6.8 RAG 知识检索（内存 / 真实向量 / 百炼 / Dify）

```yaml
customer-work.rag:
  provider: simple          # memory(关键词) | simple(百炼Embedding+内存向量库) | bailian | dify
  top-k: 3
  simple:  { dimensions: 1024 }
  bailian: { access-key-id: "", access-key-secret: "", workspace-id: "", index-id: "" }
  dify:    { api-key: "", api-base-url: "", dataset-id: "" }
```
测试：`InMemoryKeywordKnowledgeTest`、`KnowledgeProviderTest`（含 simple/dify 装配）。

### 6.9 工具集成 + 把它改成你自己的业务 Agent

四业务域 Tool Group（knowledge/order/after_sales/human）默认全激活；开启元工具后 Agent 可运行时启停工具组：
```yaml
customer-work.agent: { max-iters: 10, meta-tool-enabled: true }
```

**接入你自己的业务系统（无需改框架代码）**：业务工具壳（`OrderTools` 等）只暴露给 LLM 的 Schema，
真正逻辑委托给 `tool.backend` 下的接口（`OrderBackend` / `AfterSalesBackend` / `KnowledgeBackend`）。
默认由 `ToolBackendConfig` 以 `@ConditionalOnMissingBean` 注册内存 Mock；你只要声明自己的同类型 Bean，
默认实现就自动让位：

```java
@Component
public class MyOrderBackend implements OrderBackend {
    public Mono<String> queryOrder(String orderId) { /* WebClient 调你的订单服务 */ }
    public Mono<String> queryLogistics(String orderId) { /* ... */ }
}
```

其他定制点：系统提示词（`SYSTEM_PROMPT` 或 Nacos 热更新）、工具组（`ToolRegistrar`）、
RAG 文档（`KnowledgeProvider`）、技能（`resources/skills/<name>/SKILL.md`）。

测试：`OrderToolsTest` / `AfterSalesToolsTest` / `KnowledgeBaseToolsTest` / `HumanHandoffToolsTest`、
`ToolBackendOverrideTest`（自定义后端覆盖）、`CustomerServiceAgentFactoryTest`。

### 6.10 Skill 技能库

```yaml
customer-work.skill:
  enabled: true
  repository: filesystem        # classpath(只读内置) | filesystem(可写，技能自进化/写回)
  directory: ./data/skills
  runtime-load-tool-enabled: true   # 注册"运行时加载技能"工具
  code-execution-enabled: true      # 注册读写/Shell 代码执行工具
```
内置技能：`src/main/resources/skills/refund-handling/SKILL.md`。
测试：`SkillLoadingTest`、`FileSystemSkillRepositoryTest`、`CodeExecutionSkillTest`。

### 6.11 Human-in-the-Loop + 中断恢复

```yaml
customer-work:
  human-approval: { enabled: true, guarded-tools: [submitRefund] }   # 受控工具执行后暂停待人工
  interrupt: { pending-tool-recovery-enabled: true }                  # 中断后无缝恢复待执行工具
```
测试：`HumanApprovalHookTest`、`CustomerServiceServiceTest#interrupt_*`。

### 6.12 模型层（多厂商 + 私有化兜底 + 高级参数）

```yaml
customer-work.model:
  provider: dashscope      # dashscope | openai | anthropic | gemini | ollama
  name: qwen-max
  temperature: 0.3
  top-p:                   # GenerateOptions 高级
  reasoning-effort:        # low | medium | high
  enable-search:           # 仅 dashscope：联网搜索
  enable-thinking:         # 仅 dashscope：深度思考
  fallback: { enabled: true, provider: ollama, name: qwen2.5, base-url: "" }   # 主失败切私有化兜底
```
> anthropic/gemini 需各自厂商 SDK 依赖。测试：`ModelConfigTest`（DashScope/OpenAI 离线构建 + Fallback 切换）。

### 6.13 可观测 / 运维就绪

```bash
curl localhost:8080/actuator/health      # 含 session 后端探活
curl localhost:8080/actuator/prometheus  # 业务指标
```
- 指标：`customerwork.agent.requests / reasoning / tool.calls{tool} / errors / tokens.total`
- 原生 Tracing：`observability.tracing-enabled=true`（`LoggingTracer`，可换 OTel `TelemetryTracer`）
- 优雅停机：`runtime.shutdown-timeout-seconds`；定时巡检：`runtime.scheduler-enabled`
- 请求关联：`RequestIdWebFilter` 为每个请求生成 / 透传 `X-Request-Id`（写回响应头 + Reactor 上下文）
- 结构化日志：`logback-spring.xml` 日志含 requestId；引入 logstash-encoder 可一键切 JSON（见文件注释）
- Grafana：示例仪表盘 `docs/grafana-dashboard.json`（基于上述 Prometheus 指标）
- 测试：`ObservabilityHookTest`、`LoggingTracerTest`、`SessionHealthIndicatorTest`、`GracefulShutdownServiceTest`、`MaintenanceSchedulerTest`、`RequestIdWebFilterTest`。

### 6.13b 模型高可用与成本护栏

```yaml
customer-work.model:
  retry: { enabled: true, max-attempts: 2, backoff-ms: 500 }   # 瞬时错误指数退避重试
  token-warn-threshold: 4000                                    # 单次请求 token 超阈值打 WARN（0=关闭）
```
- `ResilientChatModel` 包装模型调用做退避重试（可与 `FallbackChatModel` 叠加：先重试、仍失败再兜底）。
- 测试：`ResilientChatModelTest`。
- 效果评估 / 回归：见 [docs/EVAL.md](docs/EVAL.md)（提示词版本化 + 评测集 + 数据飞轮）。

### 6.14 接入层安全（API Key 鉴权 + 限流）

```yaml
customer-work.security:
  auth:       { enabled: true, header-name: X-API-Key, api-keys: [your-key-1] }
  rate-limit: { enabled: true, requests-per-minute: 120 }
```
```bash
curl -X POST localhost:8080/api/customer/chat -H "X-API-Key: your-key-1" \
  -H "Content-Type: application/json" -d '{"message":"你好"}'
```
健康检查 / Actuator 免鉴权。测试：`ApiKeyAuthWebFilterTest`、`RateLimitWebFilterTest`。

### 6.15 MCP / Higress

```yaml
customer-work:
  mcp:     { enabled: true, servers: [ { name: inventory, url: http://localhost:9000/sse, transport: sse } ] }
  higress: { enabled: true, endpoint: http://higress.local/mcp/sse, transport: sse, tool-search: "订单 物流" }
```
测试：`McpToolkitConfigurerTest`、`HigressToolkitConfigurerTest`（开关与装配判定，不连真实服务）。

### 6.16 Studio / TTS

```yaml
customer-work:
  observability.studio: { enabled: true, url: ws://localhost:3000, project: customer-work }
  protocol.tts: { enabled: true }     # 需 DashScope 实时 TTS 模型；audioCallback 回推音频帧
```
测试：`ProtocolExtensionTest`（默认关闭、未配置 url 视为未启用）。

### 6.17 Nacos 配置中心（系统提示词热更新）

把系统提示词托管 Nacos，运营侧改提示词**无需重启即热更新**；Nacos 不可用时回退内置提示词。
```yaml
customer-work.nacos:
  enabled: true
  server-addr: localhost:8848
  username: nacos          # 本机默认 nacos/nacos（http://localhost:8848/nacos）
  password: nacos
  group: DEFAULT_GROUP
  prompt-data-id: customer-work-system-prompt
```
在 Nacos 控制台新增 dataId=`customer-work-system-prompt`、group=`DEFAULT_GROUP` 的配置（内容即系统提示词）即生效；之后在控制台修改内容会**实时热更新**。
测试：
- `NacosPromptServiceTest`（离线 mock `ConfigService`：初始拉取 + `ArgumentCaptor` 验证热更新 + 空白回退）；
- `NacosPromptIntegrationTest`（**真实连本机 Nacos**：发布配置→`NacosPromptService` 拉取生效→清理；Nacos 不可达时自动跳过）。

---

## 七、配置项总表

全部位于 `application.yml` 的 `customer-work.*`（支持环境变量覆盖）：

| 前缀 | 关键项（默认） |
|---|---|
| `model` | provider(dashscope), name(qwen-max), api-key(${DASHSCOPE_API_KEY}), temperature(0.3), max-tokens(1500), stream(true), top-p, reasoning-effort, enable-search, enable-thinking, embedding-name(text-embedding-v3), fallback.* |
| `session` | mode(memory), directory, redis.*, mysql.* |
| `agent` | max-iters(10), meta-tool-enabled(false) |
| `memory` | long-term-enabled(true), provider(memory), tenant-delimiter(":"), retrieve-top-k(5), bailian.*/mem0.*/reme.* |
| `plan` | enabled(true), max-subtasks(20) |
| `rag` | enabled(true), provider(memory), top-k(3), simple.dimensions(1024), bailian.*/dify.* |
| `context` | compression-enabled(false), max-token(8000), msg-threshold(40), last-keep(10) |
| `skill` | enabled(true), repository(classpath), location(skills), directory, writable(true), runtime-load-tool-enabled(false), code-execution-enabled(false) |
| `mcp` | enabled(false), servers[] |
| `observability` | trace-enabled(false), trace-file, tracing-enabled(false), studio.* |
| `human-approval` | enabled(true), guarded-tools([submitRefund]) |
| `fact-log` | enabled(true), directory(./data/facts) |
| `security` | auth.{enabled(false),header-name(X-API-Key),api-keys[]}, rate-limit.{enabled(false),requests-per-minute(120)} |
| `multi-agent` | enabled(true), mode(fanout), max-iters(6) |
| `runtime` | shutdown-timeout-seconds(30), scheduler-enabled(false), scheduler-fixed-delay-ms(60000) |
| `interrupt` | pending-tool-recovery-enabled(true) |
| `nacos` | enabled(false), server-addr(localhost:8848), namespace, group(DEFAULT_GROUP), prompt-data-id, username, password, timeout-ms(3000) |
| `higress` | enabled(false), name(higress), endpoint, transport(sse), tool-search, max-tools(10), timeout-seconds(30) |
| `protocol` | agui.{enabled(true),enable-reasoning(true),emit-tool-call-args(true)}, tts.enabled(false) |

---

## 八、测试说明

```bash
mvn test                                   # 全部单测（115 个），离线即可全绿
mvn test -Dtest=ModelConfigTest            # 单类
```

- **离线单测**用 Mockito 隔离模型/框架、`StepVerifier` 校验响应式链路、`WebTestClient` 驱动 Web 层、`@SpringBootTest` 冒烟全 Bean 装配——不调真实大模型。
- **条件集成测试**（服务可达才跑，不可达自动 `assumeTrue` 跳过，保证任何环境 `mvn test` 都绿）：
  - `RedisSessionPersistenceTest`：本机 Redis(6379, 密码 123456) 存-取-删往返
  - `MysqlSessionPersistenceTest`：本机 MySQL(3306, root/root, 库 agent_scope_customer_work)
  - `NacosPromptIntegrationTest`：本机 Nacos(8848, nacos/nacos) 发布→拉取提示词往返
  - `BailianIntegrationTest`：真实百炼调用，需 `export RUN_BAILIAN_IT=true`（消耗额度）
- **CI**：`.github/workflows/ci.yml` 在 push/PR 时跑 `mvn test` + 打包，并启动 Redis/MySQL 服务容器，使持久化用例在 CI 真实执行。

---

## 九、代码结构（多模块）

```
customer_work/                                  # 父 pom（packaging=pom，聚合两模块）
├── customer-work-spring-boot-starter/          # 【可复用 starter】无 main，作为依赖被引入
│   └── src/main/
│       ├── java/com/richard/fyoung/customerwork/
│       │   ├── autoconfigure/ CustomerWorkAutoConfiguration   # @AutoConfiguration 自动装配入口
│       │   ├── config/      CustomerWorkProperties / ModelConfig / FallbackChatModel / ResilientChatModel
│       │   │                SessionConfig / ToolBackendConfig / OpenApiConfig / NacosPromptService
│       │   ├── agent/       CustomerServiceAgentFactory / MultiAgentOrchestrator / AguiService
│       │   │                ObservabilityHook / HumanApprovalHook
│       │   ├── service/     CustomerServiceService / SessionStateManager
│       │   ├── memory/      LongTermMemoryProvider / Store / InMemoryLongTermMemory / FactLog / ContextMemoryFactory
│       │   ├── rag/         KnowledgeProvider / InMemoryKeywordKnowledge
│       │   ├── tool/        OrderTools / AfterSalesTools / KnowledgeBaseTools / HumanHandoffTools
│       │   │                ToolRegistrar / McpToolkitConfigurer / HigressToolkitConfigurer
│       │   │   └── backend/ OrderBackend / AfterSalesBackend / KnowledgeBackend（SPI）+ Mock 默认实现
│       │   ├── observability/ LoggingTracer / TracingConfig / TtsHookProvider / StudioConfigurer
│       │   ├── runtime/     GracefulShutdownService / MaintenanceScheduler
│       │   ├── security/    ApiKeyAuthWebFilter / RateLimitWebFilter / RequestIdWebFilter
│       │   ├── health/      SessionHealthIndicator
│       │   └── dto/         ChatRequest / ChatResponse / IntentResult
│       └── resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
│
├── customer-work-example/                       # 【可运行示例】依赖 starter
│   └── src/main/
│       ├── java/com/richard/fyoung/customerworkapp/    # 独立包，与 starter 基础包不重叠
│       │   ├── CustomerWorkApplication.java     # 仅 @SpringBootApplication；能力由 starter 自动装配
│       │   └── controller/                      # CustomerServiceController / GlobalExceptionHandler
│       └── resources/  application.yml / application-prod.yml / logback-spring.xml / skills/
│
├── mysql/schema.sql                            # MySQL 会话表建库脚本
├── Dockerfile / docker-compose.yml             # 多模块构建 + 一键起依赖
└── .github/workflows/ci.yml                    # CI（Redis/MySQL/Nacos 服务容器）
```

> **模块边界**：`starter` 装可复用的 Agent 基础设施与扩展点（SPI、配置、装配、记忆/RAG/安全/可观测/Nacos），
> 并通过 **`@AutoConfiguration` 自动装配**（注册于 `META-INF/spring/...AutoConfiguration.imports`）；
> `example` 只装"客服业务"（启动类、HTTP 接口、提示词/技能资源）。
>
> **下游接入（零扫描）**：任意工程只要 `引入 starter 依赖 + 写自己的 *Backend Bean / Controller`，
> 即自动获得全部能力，**无需关心自身基础包、无需手动 `@ComponentScan`**。starter 固定扫描自身包
> `com.richard.fyoung.customerwork`，与下游包名互不影响、不重复装配。

### 9.1 作为依赖引入（其他工程）

```xml
<dependency>
  <groupId>io.github.richardfyoung</groupId>
  <artifactId>customer-work-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```
```java
// 你的应用（任意包名）：引入后能力自动装配；只需提供自己的业务后端
@Component
public class MyOrderBackend implements OrderBackend { /* 调你的订单系统 */ }
```

---

## 十、未实现 / 需外部基础设施的扩展点

这些能力框架已提供，但需引入额外依赖并部署对应服务，硬编入会破坏「开箱即用 + bug-free」，故保留为**配置即用**扩展点：

| 功能 | 需要 | 落地路径 |
|---|---|---|
| **A2A Agent Card 注册发现** | Nacos AI/A2A API（`com.alibaba.nacos.api.ai.AiService`，新版 nacos-client）+ `io.a2a` SDK（`AgentCard`） | 用 `NacosAgentRegistry` / `NacosAgentCardResolver` 注册与发现 Agent Card；本项目已落地 Nacos 配置中心层 |
| **RocketMQ 异步消息** | RocketMQ broker + client 依赖 | `extensions.rocketmq` 做任务解耦 / A2A over MQ |
| **定时 Agent 调度** | Quartz 或 XXL-JOB | `QuartzAgentScheduler` / `XxlJobAgentScheduler` 定时驱动 Agent 跑批 |
| **Runtime 工具沙箱** | 独立项目 `agentscope-runtime-java` | 工具执行隔离（Shell/文件/浏览器/移动端沙箱） |
| **Training 数据飞轮** | RM Gallery + Trinity-RFT 平台 | 奖励函数评估 + 强化学习闭环 |
| **Anthropic / Gemini 模型** | 各自厂商 SDK（`com.anthropic` / `com.google.genai`） | `model.provider=anthropic/gemini`，代码已就绪，补依赖即可 |
| **RAGFlow / Haystack 知识库** | 对应 client 依赖 | 同 Dify 模式扩展 `KnowledgeProvider` |
| **Harness 长任务脚手架** | AgentScope 1.1+ | 升级框架后接入分层记忆 + 子 Agent 声明 |

> 说明：A2A 所需的新版 nacos-client（含 AI API）与 `io.a2a` SDK 在当前受限网络环境无法检索/确定可用版本，因此未强行引入。提供确切 Maven 坐标后即可补齐 A2A 注册发现 + 集成测试。

---

## 十一、重要说明

- 基于官方稳定版坐标 `io.agentscope:agentscope:1.0.12`；框架高速迭代，升级遇 API 不匹配请对照该版本源码微调。
- API Key 支持配置项与环境变量两种来源；**生产请用环境变量注入**，勿把生产密钥留在仓库。
- 工具均为异步 `Mono`，业务链路无 `.block()`；持久化落盘放在 `boundedElastic` 调度，不阻塞响应式线程。
- 所有外部后端（百炼/Redis/MySQL/Nacos/Mem0/ReMe/Dify/Higress/MCP/Studio/TTS）均为**配置开关**，默认实现保证离线开箱即用与单测全绿。
