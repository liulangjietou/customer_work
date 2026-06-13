# customer-work · 基于 AgentScope Java 的生产级智能客服系统

本项目是配套文章《AgentScope Java 生产实践深度解析》中那张客服业务流程图的**生产级代码实现**，
基于官方稳定版坐标 `io.agentscope:agentscope:1.0.12`，对接**阿里云百炼（DashScope / 通义千问）**。

## 一、它实现了流程图里的哪部分？

那张流程图是一个**生产目标形态**，分六个阶段。一半是框架能力，另一半是企业自有基础设施。
本项目的边界划分如下：

| 流程图阶段 | 本项目实现 | 说明 |
|---|---|---|
| ② 会话恢复与上下文装配 | ✅ 真实实现 | `CustomerServiceService` 按 sessionId 维护 Agent，并用框架 `Session`/`State` 做持久化恢复（内存 / Json 可切，Redis/MySQL 可扩展） |
| ③ 主 Agent 意图识别与路由 | ✅ 真实实现 | `CustomerServiceAgentFactory` 用 `ReActAgent` + 系统提示词实现意图理解、工具路由、高风险熔断 |
| ④ 子能力分层执行 | ✅ 真实实现 | 四个 **Tool Group**（知识库 / 订单 / 售后 / 人工转接）+ 涉资金人工确认 + 安全转人工 |
| ⑤ 观察-再推理循环 → 回复 | ✅ 真实实现 | `ReActAgent` 内置 ReAct 循环；SSE 接口订阅 `agent.stream()` 的类型化事件流逐片段下发 |
| ① 接入与流量治理（Higress/RocketMQ/A-B） | ⚠️ 扩展点 | 部署在应用**前面**的基础设施，本应用作为上游被网关路由，代码内不实现 |
| ⑥ 数据飞轮（OTel/RM Gallery/Trinity-RFT） | ⚠️ 扩展点 | `ObservabilityHook` 给出全链路采集打点（含 token 用量、时延、异常）；评估与强化学习对接你的平台 |

一句话：**核心 Agent 链路（②③④⑤）是框架原生支持、可直接运行的；①和⑥是生产工程化范畴，本项目以 Hook、配置项、扩展点的形式预留对接位置。**

### 已落地的特性全景（覆盖系列文章各专题）

| 特性 | 本项目落地方式 | 默认 |
|---|---|---|
| ReActAgent | `CustomerServiceAgentFactory` 装配，`max-iters` 控制轮次 | 开 |
| 流式输出 | `POST /chat/stream` 订阅 `agent.stream()` 类型化事件流逐片段 SSE 下发 | 开 |
| 结构化输出 | `POST /intent` 用 `call(msg, IntentResult.class)` 返回强类型意图 | 开 |
| 多轮会话 & 持久化 | 框架 `Session/State`，按 sessionId `saveTo`/`loadIfExists`，支持 **memory/json/redis/mysql** | 开 |
| 状态自动编排 | `SessionStateManager` 用 `SessionManager` 把多 StateModule 整体 save/load | 开 |
| 长期记忆（多租户） | `LongTermMemoryProvider` 切换：内存 / **百炼** / **Mem0** / **ReMe**，按租户隔离 | 开 |
| 智能上下文压缩 | `AutoContextMemory`，长对话自动压缩、上下文有界 | 关（需模型） |
| 三层记忆体系 | L1 短期 `Memory` + L2 长期 `LongTermMemory` + L3 只追加 `FactLog`（JSONL 审计） | 开 |
| RAG | `KnowledgeProvider` 切换：内存关键词 / **simple 向量(百炼Embedding)** / **百炼知识库** / **Dify** | 开 |
| 工具集成 | Toolkit + 四业务域 Tool Group + 可选 Meta-Tool 运行时启停 | 开 / meta 关 |
| Skill | `SkillBox`：classpath 只读 / **filesystem 可写自进化** / 运行时加载工具 / **代码执行** | 开 |
| 多 Agent 编排 | `MultiAgentOrchestrator` 用 `Pipelines` fanout/sequential 编排订单/售后/知识库专家 + `POST /consult` | 开 |
| Human-in-the-Loop | `HumanApprovalHook` 高风险工具执行后暂停待人工 + `POST /session/{id}/interrupt` 安全中断 | 开 |
| 中断恢复 | `enablePendingToolRecovery` 中断后无缝恢复待执行工具调用 | 开 |
| Hook 可观测 | `ObservabilityHook` 采集 token/时延/工具/异常（日志 + Micrometer 指标） | 开 |
| 原生 Tracing | `LoggingTracer` 注册到 `TracerRegistry` 按调用打 span（可换 OTel TelemetryTracer） | 关 |
| 运维就绪 | Actuator 健康检查（含 `SessionHealthIndicator`）+ Prometheus 指标 + 优雅停机 + 定时维护 | 开 |
| 模型层进阶 | 多厂商（dashscope/openai/anthropic/gemini/ollama）+ `FallbackChatModel` 私有化兜底 + GenerateOptions 高级 | 开 |
| 交互协议 AG-UI | `AguiService` 适配标准 AG-UI 事件流 + `POST /agui`（SSE） | 开 |
| 交互协议 TTS | `TtsHookProvider` 实时语音合成 Hook（audioCallback 回推音频） | 关 |
| MCP 接入 | `McpToolkitConfigurer` 把存量 HTTP 系统接成 Agent 工具 | 关 |
| Higress AI 网关 | `HigressToolkitConfigurer` 接入 Higress，按需工具发现 / 流量治理 | 关 |
| Studio 可视化 | `StudioConfigurer` 连接 AgentScope Studio 做可视化调试 | 关 |
| 接入层安全 | `ApiKeyAuthWebFilter`（API Key 鉴权）+ `RateLimitWebFilter`（限流） | 关 |
| Nacos 配置中心 | `NacosPromptService` 把系统提示词托管 Nacos，运营侧热更新无需重启（A/B、话术调优） | 关 |

### 需外部基础设施的扩展点（配置即用，默认关闭）

以下能力框架已提供，但需引入额外依赖并部署对应服务，按需开启（本项目预留配置位与文档）：

- **A2A Agent Card 注册发现**：跨进程 Agent 互通，需 Nacos **AI/A2A API**（`com.alibaba.nacos.api.ai.AiService`，新版 nacos-client）与 `io.a2a` SDK（`AgentCard`）；本项目已用 Nacos 配置中心（提示词热更新），A2A 注册层属此扩展点；
- **RocketMQ 异步消息**：长任务解耦 / A2A over MQ，需 RocketMQ broker；
- **定时 Agent 调度**：`QuartzAgentScheduler` / `XxlJobAgentScheduler`，需 Quartz / XXL-JOB；
- **Runtime 沙箱**：工具执行隔离，需独立项目 `agentscope-runtime-java`；
- **Training（数据飞轮）**：RM Gallery 评估 + Trinity-RFT 强化学习，需训练平台；
- **Anthropic / Gemini 模型**：需各自厂商 SDK 依赖；**RAGFlow / Haystack** 知识库同 Dify 模式可插拔。
- **Harness**：1.0.12 暂无（属 1.1+），升级后可接入分层记忆 + 子 Agent 声明。

> 每项能力均为**配置开关 + 可替换实现**：内置进程内实现保证开箱即用与可单测，
> 生产可无感切换为百炼长期记忆 / 百炼企业知识库 / Redis 会话 / 真实 MCP 服务等后端。
> 全部配置见 `application.yml` 的 `customer-work.*`。

### 会话持久化：Redis / MySQL

切换存储只改一行 `customer-work.session.mode`：

```yaml
customer-work:
  session:
    mode: redis          # memory | json | redis | mysql
    redis:   { host: localhost, port: 6379, password: "123456", key-prefix: customer-work }
    mysql:   { host: localhost, port: 3306, database: agent_scope_customer_work, username: root, password: root, auto-create: true }
```

- **Redis**：基于 Jedis 的 `RedisSession`，多实例共享会话；
- **MySQL**：基于 HikariCP + `MysqlSession`，表 `agentscope_sessions` 可自动创建。
  手工建库脚本见 `mysql/schema.sql`（`mysql -h localhost -u root -proot < mysql/schema.sql`）。

`RedisSessionPersistenceTest` / `MysqlSessionPersistenceTest` 做真实存-取-删往返验证：
**服务可达时执行并通过，不可达时自动跳过**（`assumeTrue`），因此 `mvn test` 在任何环境都绿。
（本机有 Redis/MySQL 时直接 `mvn test` 即会真实跑这两条用例。）

### 切换百炼知识库 / 百炼长期记忆

只改配置即可从内置内存实现切到阿里云百炼托管能力（代码零改动）：

```yaml
customer-work:
  rag:
    provider: bailian
    bailian: { access-key-id: ..., access-key-secret: ..., workspace-id: ..., index-id: ... }
  memory:
    provider: bailian
    bailian: { api-key: ..., memory-library-id: ..., top-k: 5 }   # api-key 留空则复用 model.api-key
```

### 接入层安全：API Key 鉴权 + 限流

默认关闭，生产开启：

```yaml
customer-work:
  security:
    auth: { enabled: true, header-name: X-API-Key, api-keys: [your-key-1, your-key-2] }
    rate-limit: { enabled: true, requests-per-minute: 120 }
```

- `ApiKeyAuthWebFilter`：校验请求头 API Key，健康检查 / Actuator 免鉴权；
- `RateLimitWebFilter`：按 API Key 或客户端 IP 固定窗限流，超限返回 429（分布式可换 Redis 限流）。

### Higress AI 网关接入

```yaml
customer-work:
  higress:
    enabled: true
    endpoint: http://higress.local/mcp/sse
    transport: sse              # sse | streamable-http
    tool-search: "订单 物流"     # 按需工具发现关键词（避免上百工具 Schema 撑爆上下文）
```

### CI

`.github/workflows/ci.yml`：push / PR 触发 `mvn test` + 打包，并启动 **Redis 与 MySQL 服务容器**
（含密码 / 库名），使会话持久化测试在 CI 中真实执行；百炼集成测试默认跳过。

### 生产可观测 / 运维就绪

应用暴露 Spring Boot Actuator 端点（`management.endpoints.web.exposure.include`）：

```bash
curl localhost:8080/actuator/health      # 含自定义 session 后端健康（memory/json/redis/mysql 探活）
curl localhost:8080/actuator/metrics     # 指标清单
curl localhost:8080/actuator/prometheus  # Prometheus 抓取端点
```

`ObservabilityHook` 在接入 Micrometer 后会上报业务指标，供监控与数据飞轮消费：

- `customerwork.agent.requests`：请求数
- `customerwork.agent.reasoning`：推理轮次数
- `customerwork.agent.tool.calls{tool=...}`：按工具维度的调用数
- `customerwork.agent.errors`：错误数
- `customerwork.agent.tokens.total`：累计 token 消耗（成本可观测）

### 对接百炼平台的真实集成测试

`BailianIntegrationTest` 真实调用百炼（DashScope），默认随 `mvn test` **跳过**以保证离线可过。
联调时：

```bash
export RUN_BAILIAN_IT=true
export DASHSCOPE_API_KEY=你的百炼密钥   # 不设则用项目默认 key
mvn test -Dtest=BailianIntegrationTest
```

## 二、环境要求

- JDK 17+（本仓库用 21 验证通过）
- Maven 3.8+
- 一个阿里云百炼（DashScope）API Key

## 三、配置

所有配置集中在 `application.yml` 的 `customer-work.*`，可被环境变量覆盖：

```yaml
customer-work:
  model:
    api-key: ${DASHSCOPE_API_KEY:...}   # 百炼 API Key，强烈建议用环境变量注入
    name: qwen-max                      # 可切 qwen-plus / qwen-turbo
    base-url: ""                        # 自定义网关 / 兼容地址，留空用 SDK 默认
    temperature: 0.3
    max-tokens: 1500
    stream: true
  session:
    mode: memory                        # memory | json（json 单机重启可恢复）
    directory: ./data/sessions
  agent:
    max-iters: 10                       # ReAct 最大轮次
```

> 安全提示：请勿把生产密钥长期留在代码仓库。生产部署用 `export DASHSCOPE_API_KEY=...` 注入。

## 四、快速开始

```bash
export DASHSCOPE_API_KEY=你的百炼密钥   # 覆盖默认值
mvn spring-boot:run
```

Web 启动后调用：

```bash
# 同步对话
curl -X POST http://localhost:8080/api/customer/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"帮我查一下订单 20260613001 的状态和物流"}'

# 流式对话（SSE，逐片段返回）
curl -N -X POST http://localhost:8080/api/customer/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"你们支持七天无理由退货吗？"}'

# 结构化意图识别（返回强类型 JSON）
curl -X POST http://localhost:8080/api/customer/intent \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"这个订单 20260613001 我要退款"}'

# 安全中断正在执行的会话
curl -X POST http://localhost:8080/api/customer/session/u1001/interrupt

# 结束会话
curl -X DELETE http://localhost:8080/api/customer/session/u1001
```

> 长期记忆支持多租户：把 sessionId 写成 `租户ID:会话ID`（如 `u1001:conv-1`），同一租户的不同会话即可共享长期记忆，租户之间严格隔离。

## 五、跑测试（无需 API Key）

单元测试用 Mockito 隔离模型与框架、用 `StepVerifier` 校验响应式链路、用 `WebTestClient` 驱动 Web 层，
全程不调真实大模型：

```bash
mvn test
```

覆盖范围（60 个用例，含 1 个默认跳过的百炼集成测试）：工具组业务逻辑、Tool Group/Meta-Tool 装配、
租户解析、RAG 召回、智能上下文压缩选择、Skill 加载、MCP 开关、Human-in-the-Loop 闸门、
多租户长期记忆与事实日志、可观测 Hook、会话服务编排、控制器路由与校验、以及全 Bean 装配冒烟测试。

## 六、代码结构

```
src/main/java/com/example/customerwork/
├── CustomerWorkApplication.java            # Spring Boot 启动类
├── config/
│   ├── CustomerWorkProperties.java         # 强类型配置（model/session/agent/memory/plan/rag/context/skill/mcp/observability/...）
│   ├── ModelConfig.java                    # 模型层：百炼 DashScope 统一抽象
│   └── SessionConfig.java                  # 会话持久化 Session Bean（memory/json/redis/mysql）
├── agent/
│   ├── CustomerServiceAgentFactory.java    # 主 Agent 装配：工具组/Meta-Tool/Plan/记忆/RAG/Skill/MCP/Hook
│   ├── ObservabilityHook.java              # 全链路采集（token/时延/异常）
│   └── HumanApprovalHook.java              # Human-in-the-Loop：高风险工具人工确认闸门
├── memory/
│   ├── LongTermMemoryStore.java            # L2 多租户长期记忆存储
│   ├── InMemoryLongTermMemory.java         # L2 LongTermMemory 接口实现（同时写 L3）
│   ├── ContextMemoryFactory.java           # 短期记忆 / 智能上下文压缩（AutoContext）
│   └── FactLog.java                        # L3 只追加事实日志（JSONL 审计）
├── rag/InMemoryKeywordKnowledge.java       # RAG：Knowledge 接口内存实现
├── tool/
│   ├── OrderTools / AfterSalesTools / KnowledgeBaseTools / HumanHandoffTools.java
│   └── McpToolkitConfigurer.java           # MCP 接入装配器
├── service/CustomerServiceService.java     # 会话恢复 / 持久化 / 流式 / 结构化意图 / 安全中断
├── controller/
│   ├── CustomerServiceController.java      # 入口：同步 / SSE 流式 / 意图 / 中断
│   └── GlobalExceptionHandler.java         # 统一错误响应
└── dto/ (ChatRequest, ChatResponse, IntentResult)

src/main/resources/skills/refund-handling/SKILL.md   # Markdown 技能（Skill 特性）
mysql/schema.sql                                      # MySQL 会话持久化建库建表脚本
```

## 七、从示例到大规模生产，还可以补什么

1. **分布式会话持久化**：把 `session.mode` 扩展为 redis/mysql，返回框架内置 `RedisSession` / `MysqlSession`，多实例共享状态。
2. **真实长期记忆**：接 `agentscope-extensions-mem0` 或百炼长期记忆，在 builder 上加 `.longTermMemory(...).longTermMemoryMode(BOTH)`。
3. **真实 RAG**：把 `KnowledgeBaseTools` 的关键词命中替换为 AgentScope 内置 Embedding RAG（`.knowledge(...)`）/ Dify / 百炼企业知识库。
4. **存量系统对接**：把工具方法体里的 Mock 换成 WebClient 调用内部微服务，或用 MCP 协议零改造接入存量 HTTP 系统。
5. **多 Agent 进程级编排**：把单 Agent + 多工具组升级为子 Agent 声明 / Pipeline 编排。
6. **接入治理与数据飞轮**：①交给 Higress + RocketMQ；⑥把 `ObservabilityHook` 改为上报 OpenTelemetry，再接 RM Gallery 评估与 Trinity-RFT。

## 八、重要说明

- 基于官方稳定版坐标 `io.agentscope:agentscope:1.0.12`。框架仍在高速迭代，若升级版本遇到 API 不匹配，请对照该版本源码微调。
- API Key 支持配置项与环境变量两种来源，生产请用环境变量。
- 工具均为异步 `Mono`，业务逻辑全程无 `.block()`；持久化落盘放在 `boundedElastic` 调度，不阻塞响应式线程。
