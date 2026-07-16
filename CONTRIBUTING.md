# 贡献指南 Contributing

感谢你对本项目的兴趣！欢迎 Issue、PR、文档与生态适配器贡献。

## 开发环境

- JDK 17+（推荐 21）、Maven 3.8+
- 跑测试：`mvn test`（离线全绿；Redis/MySQL/Nacos/百炼相关用例在对应服务不可用时自动跳过）
- 本地依赖可用 `docker compose up -d`（见 `docker-compose.yml`）一键起 Redis/MySQL/Nacos

## 提交流程

1. Fork 仓库，从 `richardfyoung/dev` 切出特性分支：`feat/xxx` 或 `fix/xxx`。
2. 保持 **`mvn test` 全绿**；新增功能请附单元测试。
3. 遵循现有代码风格（4 空格缩进、中文 Javadoc 可接受、公共类加 `@author`）。
4. 提交信息建议用 [Conventional Commits](https://www.conventionalcommits.org/)：`feat: ...` / `fix: ...` / `docs: ...`。
5. 提交 PR，填写模板，关联相关 Issue。

## 设计约定（重要）

- **每个能力都是"配置开关 + 可替换实现"**：新增外部后端时，提供接口 + 默认实现（`@ConditionalOnMissingBean`），不要写死。
- **不要把任何真实密钥提交到仓库**；一律走环境变量（见 `.env.example`）。
- 业务工具走 `tool.backend.*` 接口扩展，使用者覆盖 Bean 即可接入自有系统，详见 docs/功能与配置全量参考.md《把它改成你自己的业务 Agent》。

## 行为准则

参与本项目即表示你同意遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
