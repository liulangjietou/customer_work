# customer-work · 基于 AgentScope Java 的智能客服系统（核心链路可运行示例）

这是配套文章《AgentScope Java 生产实践深度解析》中那张客服业务流程图的**代码实现**。

## 一、它实现了流程图里的哪部分？（请先读这一段）

那张流程图是一个**生产目标形态**，分六个阶段。诚实地讲，业界不存在"一份代码开箱即用跑通全图"的东西——因为图里一半是框架能力，另一半是你公司自己的基础设施。本项目的边界划分如下：

| 流程图阶段 | 本项目是否实现 | 说明 |
|---|---|---|
| ② 会话恢复与上下文装配 | ✅ 可运行 | `CustomerServiceService` 按 sessionId 维护会话、多轮共享记忆；长期记忆/RAG 以工具与扩展点形式给出 |
| ③ 主 Agent 意图识别与路由 | ✅ 可运行 | `CustomerServiceAgentFactory` 用 ReActAgent + 系统提示词实现意图理解、工具路由、高风险熔断 |
| ④ 子 Agent 分层执行 | ✅ 可运行 | 四个工具组（订单/售后/知识库/人工转接）+ 涉资金人工确认 + 安全转人工 |
| ⑤ 观察-再推理循环 → 回复 | ✅ 可运行 | ReActAgent 内置 ReAct 循环；流式回复给出 SSE 对接骨架 |
| ① 接入与流量治理（Higress/RocketMQ/A-B） | ⚠️ 扩展点 | 属于部署在应用**前面**的基础设施，本应用作为上游被网关路由，代码内不实现 |
| ⑥ 数据飞轮（OTel/RM Gallery/Trinity-RFT） | ⚠️ 扩展点 | `ObservabilityHook` 给出数据采集打点；评估与强化学习需接你的可观测与训练平台 |

一句话：**核心 Agent 链路（②③④⑤）是框架原生支持、可直接运行的；①和⑥是生产工程化范畴，需要结合你自己的基础设施落地，本项目以注释、Hook、接口的形式预留了对接位置。**

## 二、环境要求

- JDK 17+
- Maven 3.8+
- 一个通义千问（DashScope）API Key（其他模型可在 `ModelConfig` 一行切换）

## 三、快速开始

```bash
# 1. 解压后进入项目目录
cd customer_work

# 2. 配置 API Key（切勿硬编码到代码里）
export AI_DASHSCOPE_API_KEY=你的密钥

# 3a. 命令行 Demo：不起 Web，直接在控制台跑三段典型对话（咨询/订单/退款）
mvn spring-boot:run -Dspring-boot.run.profiles=demo

# 3b. 或启动 Web 服务
mvn spring-boot:run
```

Web 启动后调用：

```bash
# 同步对话
curl -X POST http://localhost:8080/api/customer/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"帮我查一下订单 20260613001 的状态和物流"}'

# 流式对话（SSE）
curl -N -X POST http://localhost:8080/api/customer/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"u1001","message":"你们支持七天无理由退货吗？"}'
```

## 四、跑测试（无需 API Key）

工具层单测只验证响应式逻辑，不调真实模型：

```bash
mvn test
```

## 五、代码结构

```
src/main/java/com/example/customerwork/
├── CustomerWorkApplication.java        # Spring Boot 启动类
├── DemoRunner.java                     # 命令行 Demo（--demo profile）
├── config/ModelConfig.java             # 模型层：统一模型抽象（DashScope）
├── agent/
│   ├── CustomerServiceAgentFactory.java# ③④ 主Agent装配 + 工具注册
│   └── ObservabilityHook.java          # ⑥ 可观测数据采集打点
├── tool/
│   ├── OrderTools.java                 # ④ 订单/物流工具组
│   ├── AfterSalesTools.java            # ④ 售后/退款（涉资金走人工确认）
│   ├── KnowledgeBaseTools.java         # ② RAG 知识检索（伪RAG，可换真实RAG）
│   └── HumanHandoffTools.java          # ④ 人工坐席转接 / ③ 风险熔断
├── service/CustomerServiceService.java # ② 会话恢复 / ⑤ 状态管理
├── controller/CustomerServiceController.java # ① 应用入口 / ⑤ 回复
└── dto/ChatRequest.java
```

## 六、从示例到生产，还需要补什么

这些都是图中标了"扩展点"的部分，按需接入你的基础设施：

1. **会话真正持久化**：当前用进程内 Map 缓存 Agent，演示多轮连续对话。要做到"重启/扩缩容不丢会话、跨进程恢复"，需用 AgentScope 的 Session/State 把状态序列化到 Redis/DB，请求到来时按 sessionId 反序列化恢复。
2. **真实长期记忆**：放开 pom 中 `agentscope-extensions-mem0` 依赖，或接百炼长期记忆，在 Agent builder 上加 `.longTermMemory(...).longTermMemoryMode(BOTH)`。
3. **真实 RAG**：把 `KnowledgeBaseTools` 的关键词命中替换为 AgentScope 内置 Embedding RAG（私有化向量库）/ Dify / 百炼企业知识库。
4. **真实流式逐 Token**：在 Agent 侧用 `ReasoningChunkEvent` Hook 把增量文本发布到 Sink，Controller 订阅转发到 SSE。
5. **存量系统对接**：把工具方法体里的 Mock 换成 WebClient 调用内部微服务，或用 MCP 协议零改造接入存量 HTTP 系统。
6. **多 Agent 进程级编排**：把单 Agent+多工具组升级为 SequentialPipeline/FanoutPipeline 或 1.1+ HarnessAgent 的子 Agent 声明。
7. **接入治理与数据飞轮**：①交给 Higress + RocketMQ；⑥把 `ObservabilityHook` 的日志改为上报 OpenTelemetry，再接 RM Gallery 评估与 Trinity-RFT 强化学习。

## 七、重要说明

- 本项目基于 AgentScope Java 官方推荐的稳定版坐标 `io.agentscope:agentscope:1.0.12`。框架仍在高速迭代，若编译期遇到 API 不匹配，请对照你实际拉取到的版本，以官方文档与该版本源码为准微调（例如个别 builder 方法名）。
- 所有 API Key 从环境变量读取，未硬编码。
- 工具均为异步 `Mono`，Agent 逻辑中无 `.block()`（仅 DemoRunner 演示处使用，已注释说明）。
