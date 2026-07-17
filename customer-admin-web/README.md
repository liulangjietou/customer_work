# customer-admin-web · 后台管理前端

Vue3 + TypeScript + Vite + Element Plus + Pinia，配套后端 `customer-admin-server`（8082）。
含系统管理、AI 配置（智能体/模型/MCP/技能/定时任务）、智能体工作区、VibeCoding、**用户工单坐席工作台**等页面。

## 本地开发

```bash
npm install
npm run dev        # 端口 5174，接口经 vite proxy 转发到 http://localhost:8082
```

- 登录种子账号见部署文档（本地默认 admin/admin，可能已改密）。
- 菜单是数据库驱动（`sys_permission` 表）：新增页面需要「迁移种子 + `src/router/component-map.ts` 注册组件」两步，缺一菜单点开空白。
- 用户工单坐席聊天的 WebSocket **直连 8080**（凭证由 8082 的 `GET /api/ticket/ws-credential` 签发），本地需同时启动 customer-work-app-server。

## 构建

```bash
npm run build      # vue-tsc 类型检查 + vite build，产物 dist/
```

详细页面惯例与后端接口见仓库根 [README](../README.md) 与 [docs/详细技术文档.md](../docs/详细技术文档.md)。
