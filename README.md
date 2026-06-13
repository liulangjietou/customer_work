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

# 结束会话
curl -X DELETE http://localhost:8080/api/customer/session/u1001
```

## 五、跑测试（无需 API Key）

单元测试用 Mockito 隔离模型与框架、用 `StepVerifier` 校验响应式链路、用 `WebTestClient` 驱动 Web 层，
全程不调真实大模型：

```bash
mvn test
```

覆盖范围：四个工具组的业务逻辑、Tool Group 注册完整性、可观测 Hook 的只读透传与异常兜底、
会话服务的多轮复用 / 错误兜底 / 流式拼接 / 持久化、控制器的参数校验与路由。

## 六、代码结构

```
src/main/java/com/example/customerwork/
├── CustomerWorkApplication.java            # Spring Boot 启动类
├── config/
│   ├── CustomerWorkProperties.java         # 强类型配置（model/session/agent）
│   ├── ModelConfig.java                    # 模型层：百炼 DashScope 统一抽象
│   └── SessionConfig.java                  # 会话持久化 Session Bean（memory/json）
├── agent/
│   ├── CustomerServiceAgentFactory.java    # ③④ 主 Agent 装配 + Tool Group 注册
│   └── ObservabilityHook.java              # ⑥ 全链路采集（token/时延/异常）
├── tool/
│   ├── OrderTools.java                     # ④ 订单 / 物流工具组
│   ├── AfterSalesTools.java                # ④ 售后 / 退款（涉资金走人工确认）
│   ├── KnowledgeBaseTools.java             # ② RAG 知识检索（伪 RAG，可换真实 RAG）
│   └── HumanHandoffTools.java              # ④ 人工坐席转接 / ③ 风险熔断
├── service/CustomerServiceService.java     # ② 会话恢复 / ⑤ 状态持久化 / 流式编排
├── controller/
│   ├── CustomerServiceController.java      # ① 应用入口 / ⑤ 同步与 SSE 流式回复
│   └── GlobalExceptionHandler.java         # 统一错误响应
└── dto/ (ChatRequest, ChatResponse)
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
