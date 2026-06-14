# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/) 与 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [Unreleased]

### Added
- 多 Agent 编排（Pipeline fanout/sequential）、AG-UI 协议、TTS Hook
- 记忆/RAG 多后端：内存 / 百炼 / Mem0 / ReMe / 真实向量(SimpleKnowledge) / Dify
- Skill：classpath/filesystem 仓库、运行时加载、代码执行
- 模型层：多厂商（dashscope/openai/anthropic/gemini/ollama）+ 私有化兜底 FallbackChatModel
- 可观测：Micrometer 指标 + 原生 Tracing + Actuator + 优雅停机 + 定时维护
- 接入层安全：API Key 鉴权 + 限流；Nacos 配置中心（提示词热更新）
- Swagger / OpenAPI（springdoc-webflux）
- 业务工具后端接口化（`tool.backend.*`），使用者实现接口即可接自有系统
- 开源治理：LICENSE(Apache-2.0)、CONTRIBUTING、CODE_OF_CONDUCT、Issue/PR 模板、Dependabot、Docker Compose

### Security
- 移除仓库内硬编码密钥，全部改为环境变量注入

## [1.0.0]
- 基于 AgentScope Java 1.0.12 的生产级智能客服核心链路（会话/意图/工具/流式/持久化）
