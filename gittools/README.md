# gittools

`gittools` 是一个 Java 17 / Spring Boot MCP Server，通过 Streamable HTTP 提供固定单仓库的只读 GitHub/GitLab 检索能力。仓库地址、Git API token 和 MCP 入站 token 都从外部配置注入。

## 支持的工具

| 工具 | 用途 |
| --- | --- |
| `git_search_code` | 在固定仓库中搜索代码 |
| `git_get_file` | 读取指定分支或标签的文件 |
| `git_list_tree` | 列出仓库目录树 |
| `git_list_commits` | 查询提交记录 |
| `git_get_pull_request` | 查询 GitHub Pull Request 或 GitLab Merge Request |
| `git_search_issues` | 搜索仓库 Issue |

所有工具都标记为只读，服务端不会执行 clone、push、merge、issue 写入或任意 shell 命令。

## 本地运行

要求 Java 17、Maven 3.9+。

```bash
cd gittools
cp config.example.yml config.local.yml
export GITTOOLS_GIT_TOKEN='真实的 GitHub 或 GitLab token'
export GITTOOLS_SERVER_TOKEN='给 MCP 客户端使用的入站 token'
mvn spring-boot:run -Dspring-boot.run.arguments='--spring.config.additional-location=optional:file:./config.local.yml'
```

也可以完全使用环境变量：

```bash
GITTOOLS_PROVIDER=github \
GITTOOLS_REPOSITORY=https://github.com/owner/repo \
GITTOOLS_GIT_TOKEN='...' \
GITTOOLS_SERVER_TOKEN='...' \
mvn spring-boot:run
```

启动后：

```bash
curl http://127.0.0.1:3002/health
```

`/health` 不需要 MCP token；`/mcp` 必须带 `Authorization: Bearer $GITTOOLS_SERVER_TOKEN`。

## MCP 测试

MCP 客户端需要先完成 `initialize`，再携带返回的 `Mcp-Session-Id` 请求工具列表：

```bash
INIT_RESPONSE=$(curl -sS -D - http://127.0.0.1:3002/mcp \
  -H "Authorization: Bearer $GITTOOLS_SERVER_TOKEN" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}')
SESSION_ID=$(printf '%s\n' "$INIT_RESPONSE" | awk 'tolower($1)=="mcp-session-id:" {print $2}' | tr -d '\r')

curl -sS http://127.0.0.1:3002/mcp \
  -H "Authorization: Bearer $GITTOOLS_SERVER_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

调用读取文件工具：

```bash
curl -sS http://127.0.0.1:3002/mcp \
  -H "Authorization: Bearer $GITTOOLS_SERVER_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"git_get_file","arguments":{"path":"README.md","ref":"main"}}}'
```

## 接入当前项目

在管理端新增 MCP 时使用：

- 类型：`http`（当前项目将该类型映射到 Streamable HTTP）
- URL：`http://gittools:3002/mcp`
- Header：`Authorization: Bearer <GITTOOLS_SERVER_TOKEN>`，生产环境建议使用 SecretRef 注入

## GitLab 配置

```yaml
gittools:
  provider: gitlab
  repository: https://gitlab.example.com/team/platform/demo
  api-base-url: https://gitlab.example.com/api/v4
  token: ${GITTOOLS_GIT_TOKEN}
  server-token: ${GITTOOLS_SERVER_TOKEN}
```

GitLab 的嵌套 group 会整体作为单仓库路径编码；服务仍只允许访问这一个配置仓库。
