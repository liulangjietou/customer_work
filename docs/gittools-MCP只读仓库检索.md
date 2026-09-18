# gittools：只读 Git 仓库检索 MCP 端点

gittools 已并入 `customer-admin-server`（包 `com.richard.fyoung.customeradmin.gittools`），以 MCP Streamable HTTP
在 admin 进程内提供固定单仓库的只读 GitHub/GitLab 检索能力。**默认不启用**，由 `admin.gittools.enabled` 控制；
关闭时不装配任何 Bean、不注册任何路由。

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

## 启用

| 配置项 | 环境变量 | 说明 |
| --- | --- | --- |
| `admin.gittools.enabled` | `ADMIN_GITTOOLS_ENABLED` | 默认 `false` |
| `admin.gittools.provider` | —（relaxed binding 可直接用 `ADMIN_GITTOOLS_PROVIDER`） | `github`（默认）/ `gitlab` |
| `admin.gittools.repository` | `ADMIN_GITTOOLS_REPOSITORY` | 仓库地址，启用时必填 |
| `admin.gittools.api-base-url` | `ADMIN_GITTOOLS_API_BASE_URL` | 留空则 GitHub 用 `https://api.github.com`，GitLab 由仓库地址推导 `/api/v4` |
| `admin.gittools.token` | `ADMIN_GITTOOLS_GIT_TOKEN` | 访问 Git API 的出站 token，启用时必填 |
| `admin.gittools.server-token` | `ADMIN_GITTOOLS_SERVER_TOKEN` | MCP 客户端的入站 Bearer Token，启用时必填；生产 profile 要求至少 32 字节 |

其余参数（`ref`、`connect-timeout`、`read-timeout`、`max-response-bytes`、`max-file-bytes`）默认值见
`GitToolsProperties`。启用后漏配必填项时应用启动即失败。

端点为 `http://<admin>:8082/gittools/mcp`，在 `/api/**` 之外，不走后台登录态，只认
`Authorization: Bearer <server-token>`。

## MCP 测试

```bash
INIT_RESPONSE=$(curl -sS -D - http://127.0.0.1:8082/gittools/mcp \
  -H "Authorization: Bearer $ADMIN_GITTOOLS_SERVER_TOKEN" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}')
SESSION_ID=$(printf '%s\n' "$INIT_RESPONSE" | awk 'tolower($1)=="mcp-session-id:" {print $2}' | tr -d '\r')

curl -sS http://127.0.0.1:8082/gittools/mcp \
  -H "Authorization: Bearer $ADMIN_GITTOOLS_SERVER_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

## 在管理端登记为 MCP

- 类型：`http`（当前项目将该类型映射到 Streamable HTTP）
- URL：`http://<admin 主机>:8082/gittools/mcp`
- Header：`Authorization: Bearer <server-token>`，生产环境建议使用 SecretRef 注入

## GitLab 配置

```yaml
admin:
  gittools:
    enabled: true
    provider: gitlab
    repository: https://gitlab.example.com/team/platform/demo
    api-base-url: https://gitlab.example.com/api/v4
```

GitLab 的嵌套 group 会整体作为单仓库路径编码；服务仍只允许访问这一个配置仓库。
